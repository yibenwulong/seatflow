package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.booking.*;
import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.common.ApiException;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService {
    private final PaymentRepository records;
    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final OutboxRepository outbox;
    private final Map<String,PaymentGateway> gateways=new HashMap<>();
    private final Clock clock;
    private final TransactionTemplate local,required,outside;
    public RefundService(PaymentRepository records,OrderRepository orders,CatalogRepository catalog,OutboxRepository outbox,
                         List<PaymentGateway> gateways,PlatformTransactionManager tm,Clock clock) {
        this.records=records; this.orders=orders; this.catalog=catalog; this.outbox=outbox; this.clock=clock;
        gateways.forEach(g->this.gateways.put(g.provider(),g));
        local=new TransactionTemplate(tm); local.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        required=new TransactionTemplate(tm);
        outside=new TransactionTemplate(tm); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public RefundView request(long userId,long orderId) {
        return required.execute(s->{
            var order=orders.lockOrder(orderId).orElseThrow(()->ApiException.notFound("Order")); PaymentService.owner(userId,order);
            var existing=records.normal(orderId);
            if(existing.isPresent()) return existing.get().view();
            Instant now=clock.instant();
            if(!order.status().equals("PAID")) throw PaymentService.conflict("ORDER_NOT_REFUNDABLE");
            if(!now.isBefore(catalog.findSession(order.sessionId()).orElseThrow().startsAt())) throw PaymentService.conflict("SHOW_ALREADY_STARTED");
            var refund=records.enqueue(records.applied(order.id()),"NORMAL",now);
            records.orderState(order,"REFUNDING",now);
            outbox.append("REFUND_REQUESTED",order.id(),PaymentService.payload(order));
            return refund.view();
        });
    }
    public List<RefundView> list(long userId,long orderId) {
        PaymentService.owner(userId,orders.findOrder(orderId).orElseThrow(()->ApiException.notFound("Order")));
        return records.refunds(orderId).stream().map(PaymentRepository.Refund::view).toList();
    }
    /** Worker entry point. External calls run with no active caller transaction. */
    public void process(long refundId) {
        outside.executeWithoutResult(ignored->{
            var initial=records.refund(refundId,false);
            if(initial.isEmpty()) return;
            long orderId=initial.get().orderId();
            var claimed=local.execute(s->{
                orders.lockOrder(orderId).orElseThrow();
                var refund=records.refund(refundId,true).orElseThrow(); Instant now=clock.instant();
                if(Set.of("COMPLETED","FAILED").contains(refund.status())
                        || (refund.nextAttemptAt()!=null && now.isBefore(refund.nextAttemptAt()))
                        || (refund.leaseUntil()!=null && now.isBefore(refund.leaseUntil()))) return null;
                if(refund.attempts()>=10) { records.failed(refund,now,"RETRY_EXHAUSTED: final attempt lease expired"); return null; }
                records.claim(refund,now); return records.refund(refundId,true).orElseThrow();
            });
            if(claimed==null) return;
            String phase="GATEWAY";
            try {
                if(claimed.gatewaySucceededAt()==null) {
                    var receipt=records.receipt(claimed.receiptId());
                    var gateway=gateways.get(receipt.provider());
                    if(gateway==null) throw new IllegalStateException("Refund provider is not configured");
                    var result=gateway.refund(new RefundInstruction(claimed.refundNo(),receipt.transactionId(),claimed.amount()));
                    phase="GATEWAY_CHECKPOINT";
                    local.executeWithoutResult(s->{
                        orders.lockOrder(orderId).orElseThrow(); var refund=records.refund(refundId,true).orElseThrow();
                        if(!refund.status().equals("COMPLETED") && refund.gatewaySucceededAt()==null) records.gatewaySucceeded(refundId,result,clock.instant());
                    });
                }
                phase="LOCAL_COMPLETION";
                local.executeWithoutResult(s->{
                    var order=orders.lockOrder(orderId).orElseThrow(); var refund=records.refund(refundId,true).orElseThrow();
                    if(refund.status().equals("COMPLETED")) return;
                    if(refund.gatewaySucceededAt()==null) throw new IllegalStateException("Refund has no confirmed gateway success");
                    Instant now=clock.instant();
                    if(refund.type().equals("NORMAL")) {
                        if(!order.status().equals("REFUNDING")) throw new IllegalStateException("Normal refund order is not REFUNDING");
                        records.releaseSold(order,orders.items(order.id()));
                        records.orderState(order,"REFUNDED",now);
                        outbox.append("ORDER_REFUNDED",order.id(),PaymentService.payload(order));
                    } else outbox.append("PAYMENT_COMPENSATION_COMPLETED",order.id(),PaymentService.payload(order));
                    records.completed(refund,now);
                });
            } catch(RuntimeException failure) {
                // Store useful safe diagnostics without gateway credentials/response bodies.
                String reason=phase+" failed ("+(failure instanceof ApiException api?api.code():failure.getClass().getSimpleName())+")";
                local.executeWithoutResult(s->{
                    orders.lockOrder(orderId).orElseThrow(); var refund=records.refund(refundId,true).orElseThrow();
                    // A lease can expire during a slow external call. An old failure must
                    // not clear a newer worker's lease, attempt count, or completion.
                    if(!refund.status().equals("COMPLETED") && refund.attempts()==claimed.attempts()) records.failed(refund,clock.instant(),reason);
                });
                throw failure;
            }
        });
    }
}
