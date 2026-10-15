package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.catalog.*;
import io.github.yibenwulong.seatflow.common.ApiException;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import io.github.yibenwulong.seatflow.seating.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final OutboxRepository outbox;
    private final Clock clock;
    private final TransactionTemplate attempt;
    private final TransactionTemplate required;
    private final AdjacentSeatAllocator allocator=new AdjacentSeatAllocator();

    public OrderService(OrderRepository orders,CatalogRepository catalog,OutboxRepository outbox,
                        PlatformTransactionManager transactions,Clock clock) {
        this.orders=orders; this.catalog=catalog; this.outbox=outbox; this.clock=clock;
        attempt=new TransactionTemplate(transactions);
        attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        required=new TransactionTemplate(transactions); // REQUIRED: payment may already hold this order lock.
    }

    public OrderView create(long userId,CreateOrderRequest request) {
        validate(request);
        String digest=digest(request);
        Set<List<Long>> tried=new HashSet<>();
        int limit=request.mode()==SelectionMode.ADJACENT?3:1;
        for(int index=0;index<limit;index++) {
            try { return attempt.execute(status->createAttempt(userId,request,digest,tried)); }
            catch(DuplicateKeyException duplicate) {
                // The unique key serializes concurrent identical requests. Read after loser rollback,
                // in a fresh transaction (also avoids any outer REPEATABLE_READ snapshot).
                return attempt.execute(status->replay(orders.findRequest(userId,request.requestId())
                        .orElseThrow(()->duplicate),digest));
            } catch(SeatConflict | PessimisticLockingFailureException conflict) {
                if(index==limit-1) throw conflict("SEATS_UNAVAILABLE","Requested seats are unavailable");
            }
        }
        throw conflict("SEATS_UNAVAILABLE","Requested seats are unavailable");
    }

    private OrderView createAttempt(long userId,CreateOrderRequest request,String digest,Set<List<Long>> tried) {
        var existing=orders.findRequest(userId,request.requestId());
        if(existing.isPresent()) return replay(existing.get(),digest);
        var session=catalog.findSession(request.sessionId()).orElseThrow(()->ApiException.notFound("Session"));
        Instant now=clock.instant();
        if(!"ON_SALE".equals(session.status()) || now.isBefore(session.salesStartAt())
                || !now.isBefore(session.salesEndAt()) || !now.isBefore(session.startsAt()))
            throw conflict("SESSION_NOT_ON_SALE","Session is outside its sales window");
        Instant expiry=now.plus(Duration.ofMinutes(15));
        if(session.startsAt().isBefore(expiry)) expiry=session.startsAt();
        // Acquire the durable idempotency key before inspecting availability. Otherwise a
        // READ_COMMITTED loser could see the winner's holds and return a seat conflict.
        // This provisional amount is never visible outside this all-or-none transaction.
        long orderId=orders.insert(userId,request,digest,BigDecimal.ZERO,now,expiry);
        // Always read the authoritative snapshot; the display cache cannot establish ownership.
        var seats=catalog.listSessionSeats(request.sessionId());
        List<Long> ids;
        if(request.mode()==SelectionMode.EXPLICIT) ids=request.seatIds().stream().sorted().toList();
        else {
            var views=seats.stream().map(s->new SeatView(s.seatId(),s.rowNo(),s.seatNo(),s.position(),s.blockId(),s.priceZone(),s.price(),s.status(),s.x(),s.y())).toList();
            ids=allocator.candidates(views,request.count(),request.priceZone()).stream()
                    .map(candidate->candidate.stream().sorted().toList()).filter(candidate->!tried.contains(candidate))
                    .findFirst().orElseThrow(()->conflict("SEATS_UNAVAILABLE","No adjacent seats available"));
        }
        tried.add(ids);
        Map<Long,SessionSeat> byId=new HashMap<>(); seats.forEach(seat->byId.put(seat.seatId(),seat));
        List<SessionSeat> selected=new ArrayList<>();
        for(long id:ids) {
            var seat=byId.get(id);
            if(seat==null) throw invalid("Seat does not belong to this session");
            if(!"AVAILABLE".equals(seat.status())) throw new SeatConflict();
            selected.add(seat);
        }
        var amount=selected.stream().map(SessionSeat::price).reduce(BigDecimal.ZERO,BigDecimal::add);
        orders.setAmount(orderId,amount);
        // Single-row conditional updates in physical seat ID order give deterministic lock order.
        // Any miss throws out of the transaction, rolling back all preceding holds and the header.
        for(var seat:selected) {
            if(!orders.hold(request.sessionId(),seat.seatId(),orderId,expiry)) throw new SeatConflict();
            orders.item(orderId,seat);
        }
        outbox.append("ORDER_CREATED",orderId,payload(orderId,request.sessionId(),expiry));
        return view(orders.findOrder(orderId).orElseThrow());
    }

    public OrderView get(long userId,long orderId) {
        var order=orders.findOrder(orderId).orElseThrow(()->ApiException.notFound("Order"));
        owner(userId,order); return view(order);
    }
    public List<OrderView> list(long userId) { return orders.list(userId).stream().map(this::view).toList(); }

    public OrderView cancel(long userId,long orderId) {
        return required.execute(status->{
            var order=orders.lockOrder(orderId).orElseThrow(()->ApiException.notFound("Order"));
            owner(userId,order);
            if("CANCELLED".equals(order.status())) return view(order);
            if(!"UNPAID".equals(order.status())) throw conflict("ORDER_NOT_CANCELLABLE","Only unpaid orders can be cancelled");
            release(order,clock.instant());
            return view(orders.findOrder(orderId).orElseThrow());
        });
    }

    /** REQUIRED, including when payment already holds this row. True only for a new cancellation. */
    public boolean expire(long orderId,Instant now) {
        Objects.requireNonNull(now,"now");
        return Boolean.TRUE.equals(required.execute(status->{
            var locked=orders.lockOrder(orderId);
            if(locked.isEmpty()) return false;
            var order=locked.get();
            if(!"UNPAID".equals(order.status()) || now.isBefore(order.expireAt())) return false;
            release(order,now); return true;
        }));
    }

    private void release(LockedOrder order,Instant now) {
        int expected=orders.items(order.id()).size();
        if(orders.releaseHeld(order)!=expected) throw new IllegalStateException("Held ownership does not match locked order");
        orders.cancel(order,now);
        outbox.append("ORDER_CANCELLED",order.id(),payload(order.id(),order.sessionId(),order.expireAt()));
    }
    private OrderView replay(LockedOrder order,String digest) {
        if(!order.requestDigest().equals(digest)) throw conflict("IDEMPOTENCY_CONFLICT","Request ID was already used for a different payload");
        return view(order);
    }
    private OrderView view(LockedOrder order) {
        return new OrderView(order.id(),order.sessionId(),order.requestId(),order.selectionMode(),order.status(),order.totalAmount(),order.expireAt(),order.createdAt(),orders.items(order.id()));
    }
    private static void owner(long userId,LockedOrder order) {
        if(userId!=order.userId()) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Order belongs to another user");
    }
    private static void validate(CreateOrderRequest r) {
        if(r==null || r.requestId()==null || r.requestId().isBlank() || r.requestId().length()>100 || r.sessionId()<=0 || r.mode()==null)
            throw invalid("Request ID, session and mode are required");
        if(r.mode()==SelectionMode.EXPLICIT) {
            if(r.count()!=null || r.priceZone()!=null || r.seatIds()==null || r.seatIds().isEmpty() || r.seatIds().size()>4
                    || r.seatIds().stream().anyMatch(id->id==null || id<=0) || new HashSet<>(r.seatIds()).size()!=r.seatIds().size())
                throw invalid("Explicit selection requires 1 to 4 distinct seat IDs and no automatic selection fields");
        } else if((r.seatIds()!=null && !r.seatIds().isEmpty()) || r.count()==null || r.count()<2 || r.count()>4
                || (r.priceZone()!=null && (r.priceZone().isBlank() || r.priceZone().length()>40)))
            throw invalid("Adjacent selection requires a count from 2 to 4 and no seat IDs");
    }
    private static String digest(CreateOrderRequest r) {
        String canonical=r.sessionId()+"|"+r.mode()+"|"+(r.mode()==SelectionMode.EXPLICIT?r.seatIds().stream().sorted().toList():r.count()+"|"+Objects.toString(r.priceZone(),""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String payload(long orderId,long sessionId,Instant expiry) {
        return "{\"orderId\":\""+orderId+"\",\"sessionId\":\""+sessionId+"\",\"expireAt\":\""+expiry+"\"}";
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
    private static final class SeatConflict extends RuntimeException {}
}
