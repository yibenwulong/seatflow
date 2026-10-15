package io.github.yibenwulong.seatflow.payment;

import io.github.yibenwulong.seatflow.booking.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** Funds records and ownership-conditional mutations. Callers serialize mutations on the order row first. */
@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Intent(long id,long orderId,String provider,String merchant,BigDecimal amount) {}
    record Receipt(long id,long orderId,long intentId,String provider,String transactionId,BigDecimal amount,String status) {}
    record Refund(long id,long orderId,long receiptId,String refundNo,String type,BigDecimal amount,String status,
                  String gatewayRefundId,Instant gatewaySucceededAt,int attempts,Instant nextAttemptAt,Instant leaseUntil,String lastError) {
        RefundView view() { return new RefundView(id,orderId,refundNo,type,amount,status,attempts,nextAttemptAt,lastError); }
    }
    private static final RowMapper<Intent> INTENT=(r,n)->new Intent(r.getLong("id"),r.getLong("order_id"),r.getString("provider"),r.getString("merchant_request_no"),r.getBigDecimal("amount"));
    private static final RowMapper<Receipt> RECEIPT=(r,n)->new Receipt(r.getLong("id"),r.getLong("order_id"),r.getLong("payment_intent_id"),r.getString("provider"),r.getString("gateway_transaction_id"),r.getBigDecimal("amount"),r.getString("status"));
    private static final RowMapper<Refund> REFUND=(r,n)->new Refund(r.getLong("id"),r.getLong("order_id"),r.getLong("payment_record_id"),r.getString("refund_no"),r.getString("type"),r.getBigDecimal("amount"),r.getString("status"),r.getString("gateway_refund_id"),instant(r,"gateway_succeeded_at"),r.getInt("attempts"),instant(r,"next_attempt_at"),instant(r,"lease_until"),r.getString("last_error"));
    Optional<Intent> intent(String merchant) { return jdbc.query("SELECT * FROM payment_intent WHERE merchant_request_no=?",INTENT,merchant).stream().findFirst(); }
    Optional<Intent> intent(long id) { return jdbc.query("SELECT * FROM payment_intent WHERE id=?",INTENT,id).stream().findFirst(); }
    Intent prepare(LockedOrder order,String provider,Instant now) {
        var old=jdbc.query("SELECT * FROM payment_intent WHERE order_id=? AND provider=? FOR UPDATE",INTENT,order.id(),provider);
        if(!old.isEmpty()) return old.get(0);
        String merchant="pay-"+UUID.randomUUID();
        jdbc.update("INSERT INTO payment_intent(order_id,provider,merchant_request_no,amount,status,created_at,updated_at) VALUES(?,?,?,?,'CREATED',?,?)",order.id(),provider,merchant,order.totalAmount(),utc(now),utc(now));
        return intent(merchant).orElseThrow();
    }
    Optional<Receipt> receipt(String transaction) { return jdbc.query("SELECT * FROM payment_record WHERE gateway_transaction_id=? FOR UPDATE",RECEIPT,transaction).stream().findFirst(); }
    Receipt receipt(long id) { return jdbc.query("SELECT * FROM payment_record WHERE id=?",RECEIPT,id).stream().findFirst().orElseThrow(); }
    Receipt applied(long order) { return jdbc.query("SELECT * FROM payment_record WHERE order_id=? AND status='APPLIED' ORDER BY id FOR UPDATE",RECEIPT,order).stream().findFirst().orElseThrow(); }
    boolean hasCollection(long order) {
        // Current read: a caller may have established a repeatable-read snapshot
        // before waiting for the order lock held by the first collection.
        return !jdbc.query("SELECT id FROM payment_record WHERE order_id=? ORDER BY id LIMIT 1 FOR UPDATE",(r,n)->r.getLong(1),order).isEmpty();
    }
    Receipt received(Intent intent,VerifiedPayment payment,String status,Instant now) {
        jdbc.update("INSERT INTO payment_record(payment_intent_id,order_id,provider,gateway_transaction_id,amount,status,received_at,processed_at) VALUES(?,?,?,?,?,?,?,?)",intent.id(),intent.orderId(),intent.provider(),payment.transactionId(),payment.amount(),status,utc(now),utc(now));
        jdbc.update("UPDATE payment_intent SET status='COLLECTED',updated_at=? WHERE id=?",utc(now),intent.id());
        return receipt(payment.transactionId()).orElseThrow();
    }
    Refund enqueue(Receipt receipt,String type,Instant now) {
        var old=jdbc.query("SELECT * FROM refund_record WHERE payment_record_id=? AND type=? FOR UPDATE",REFUND,receipt.id(),type);
        if(!old.isEmpty()) return old.get(0);
        String number="refund-"+UUID.randomUUID();
        jdbc.update("INSERT INTO refund_record(refund_no,order_id,payment_record_id,type,amount,status,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',?,?,?)",number,receipt.orderId(),receipt.id(),type,receipt.amount(),utc(now),utc(now),utc(now));
        return jdbc.query("SELECT * FROM refund_record WHERE refund_no=?",REFUND,number).get(0);
    }
    Optional<Refund> refund(long id,boolean lock) { return jdbc.query("SELECT * FROM refund_record WHERE id=?"+(lock?" FOR UPDATE":""),REFUND,id).stream().findFirst(); }
    List<Refund> refunds(long order) { return jdbc.query("SELECT * FROM refund_record WHERE order_id=? ORDER BY id",REFUND,order); }
    Optional<Refund> normal(long order) { return jdbc.query("SELECT * FROM refund_record WHERE order_id=? AND type='NORMAL' FOR UPDATE",REFUND,order).stream().findFirst(); }
    void claim(Refund refund,Instant now) {
        jdbc.update("UPDATE refund_record SET status='PROCESSING',attempts=attempts+1,lease_until=?,updated_at=? WHERE id=?",utc(now.plusSeconds(30)),utc(now),refund.id());
    }
    void gatewaySucceeded(long id,GatewayRefund result,Instant now) {
        jdbc.update("UPDATE refund_record SET status='GATEWAY_SUCCEEDED',gateway_refund_id=?,gateway_succeeded_at=?,updated_at=? WHERE id=?",result.gatewayRefundId(),utc(now),utc(now),id);
    }
    void completed(Refund refund,Instant now) {
        jdbc.update("UPDATE refund_record SET status='COMPLETED',local_completed_at=?,lease_until=NULL,next_attempt_at=NULL,last_error=NULL,updated_at=? WHERE id=?",utc(now),utc(now),refund.id());
        jdbc.update("UPDATE payment_record SET status='REFUNDED',processed_at=? WHERE id=?",utc(now),refund.receiptId());
    }
    void failed(Refund refund,Instant now,String error) {
        String state=refund.attempts()>=10?"FAILED":refund.gatewaySucceededAt()!=null?"GATEWAY_SUCCEEDED":"PENDING";
        long delay=Math.min(60,1L<<Math.min(6,Math.max(0,refund.attempts()-1)));
        jdbc.update("UPDATE refund_record SET status=?,lease_until=NULL,next_attempt_at=?,last_error=?,updated_at=? WHERE id=?",state,state.equals("FAILED")?null:utc(now.plusSeconds(delay)),error,utc(now),refund.id());
    }
    void orderState(LockedOrder order,String target,Instant now) {
        if(jdbc.update("UPDATE ticket_order SET status=?,updated_at=? WHERE id=? AND status=?",target,utc(now),order.id(),order.status())!=1) throw new IllegalStateException("Locked order changed");
    }
    void sell(LockedOrder order,List<OrderItemView> items) {
        for(var item:items) if(jdbc.update("UPDATE session_seat SET status='SOLD',hold_expires_at=NULL WHERE session_id=? AND seat_id=? AND owner_order_id=? AND status='HELD'",order.sessionId(),item.seatId(),order.id())!=1) throw new IllegalStateException("Held seat ownership mismatch");
    }
    void releaseSold(LockedOrder order,List<OrderItemView> items) {
        for(var item:items) if(jdbc.update("UPDATE session_seat SET status='AVAILABLE',owner_order_id=NULL,hold_expires_at=NULL WHERE session_id=? AND seat_id=? AND owner_order_id=? AND status='SOLD'",order.sessionId(),item.seatId(),order.id())!=1) throw new IllegalStateException("Sold seat ownership mismatch");
    }
    static LocalDateTime utc(Instant now) { return LocalDateTime.ofInstant(now,ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { var value=rs.getObject(column,LocalDateTime.class); return value==null?null:value.toInstant(ZoneOffset.UTC); }
}
