package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.booking.*;
import io.github.yibenwulong.seatflow.common.ApiException;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private final PaymentRepository records;
    private final OrderRepository orders;
    private final OrderService booking;
    private final OutboxRepository outbox;
    private final Map<String,PaymentGateway> gateways;
    private final Clock clock;
    private final String provider;
    private final TransactionTemplate local,required,outside;
    public PaymentService(PaymentRepository records,OrderRepository orders,OrderService booking,OutboxRepository outbox,
            List<PaymentGateway> gateways,PlatformTransactionManager tm,Clock clock,@Value("${seatflow.payment.provider:mock}") String provider) {
        this.records=records; this.orders=orders; this.booking=booking; this.outbox=outbox; this.clock=clock; this.provider=provider;
        this.gateways=new HashMap<>(); gateways.forEach(g->this.gateways.put(g.provider(),g));
        local=new TransactionTemplate(tm); local.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        required=new TransactionTemplate(tm);
        outside=new TransactionTemplate(tm); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public PaymentView create(long userId,long orderId) {
        return outside.execute(ignored->{
            var gateway=gateway(provider);
            var intent=local.execute(s->{
                var order=orders.lockOrder(orderId).orElseThrow(()->ApiException.notFound("Order")); owner(userId,order);
                Instant now=clock.instant();
                if(!order.status().equals("UNPAID") || !now.isBefore(order.expireAt())) throw conflict("ORDER_NOT_PAYABLE");
                return records.prepare(order,provider,now);
            });
            var result=gateway.create(new PaymentIntent(intent.merchant(),intent.orderId(),intent.amount()));
            return new PaymentView(intent.id(),intent.orderId(),intent.provider(),intent.merchant(),result.transactionId(),intent.amount(),result.checkoutUrl());
        });
    }
    public void confirm(VerifiedPayment payment) {
        required.executeWithoutResult(s->{
            if(payment==null || payment.amount()==null || payment.transactionId()==null || payment.transactionId().isBlank() || payment.transactionId().length()>100) throw invalid();
            var intent=records.intent(payment.merchantRequestNo()).orElseThrow(PaymentService::invalid);
            var order=orders.lockOrder(intent.orderId()).orElseThrow(()->ApiException.notFound("Order"));
            Instant now=clock.instant(); // Must be sampled after obtaining the serializing order lock.
            if(!intent.provider().equals(payment.provider()) || intent.amount().compareTo(payment.amount())!=0 || order.totalAmount().compareTo(payment.amount())!=0) throw invalid();
            var old=records.receipt(payment.transactionId());
            if(old.isPresent()) {
                var receipt=old.get();
                if(receipt.intentId()!=intent.id() || !receipt.provider().equals(payment.provider()) || receipt.amount().compareTo(payment.amount())!=0) throw invalid();
                return;
            }
            if(order.status().equals("UNPAID") && now.isBefore(order.expireAt())) {
                records.received(intent,payment,"APPLIED",now);
                records.sell(order,orders.items(order.id())); records.orderState(order,"PAID",now);
                outbox.append("ORDER_PAID",order.id(),payload(order));
            } else {
                String type=records.hasCollection(order.id())?"DUPLICATE_PAYMENT":"LATE_PAYMENT";
                if(order.status().equals("UNPAID")) booking.expire(order.id(),now);
                var receipt=records.received(intent,payment,"COMPENSATING",now);
                records.enqueue(receipt,type,now);
                outbox.append("PAYMENT_COMPENSATION_REQUESTED",order.id(),payload(order));
            }
        });
    }
    public void notify(String provider,NotificationInput input) { confirm(gateway(provider).verifyNotification(input)); }
    public void completeMock(long userId,long paymentIntentId) {
        var intent=records.intent(paymentIntentId).orElseThrow(()->ApiException.notFound("Payment"));
        owner(userId,orders.findOrder(intent.orderId()).orElseThrow(()->ApiException.notFound("Order")));
        if(!intent.provider().equals("mock")) throw conflict("NOT_MOCK_PAYMENT");
        var mock=(MockPaymentGateway)gateway("mock");
        var payment=mock.create(new PaymentIntent(intent.merchant(),intent.orderId(),intent.amount()));
        confirm(mock.complete(payment.transactionId()));
    }
    private PaymentGateway gateway(String name) { var g=gateways.get(name); if(g==null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"GATEWAY_UNAVAILABLE","Payment provider is not configured"); return g; }
    static void owner(long userId,LockedOrder order) { if(order.userId()!=userId) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Order belongs to another user"); }
    static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Order state does not permit this operation"); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NOTIFICATION","Payment does not match its intent"); }
    static String payload(LockedOrder o) { return "{\"orderId\":\""+o.id()+"\",\"sessionId\":\""+o.sessionId()+"\",\"expireAt\":\""+o.expireAt()+"\"}"; }
}
