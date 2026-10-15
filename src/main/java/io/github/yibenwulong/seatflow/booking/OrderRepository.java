package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.catalog.SessionSeat;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;
    public OrderRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String COLUMNS="id,user_id,session_id,request_id,request_digest,selection_mode,status,total_amount,expire_at,created_at,updated_at";
    private static final RowMapper<LockedOrder> ROW=(rs,n)->new LockedOrder(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("session_id"),rs.getString("request_id"),rs.getString("request_digest"),SelectionMode.valueOf(rs.getString("selection_mode")),rs.getString("status"),rs.getBigDecimal("total_amount"),instant(rs,"expire_at"),instant(rs,"created_at"),instant(rs,"updated_at"));

    /** All payment/cancel/expiry workflows lock the order before touching its seats. */
    public Optional<LockedOrder> lockOrder(long orderId) {
        requireTransaction();
        return jdbc.query("SELECT "+COLUMNS+" FROM ticket_order WHERE id=? FOR UPDATE",ROW,orderId).stream().findFirst();
    }
    public Optional<LockedOrder> findOrder(long orderId) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ticket_order WHERE id=?",ROW,orderId).stream().findFirst();
    }
    public Optional<LockedOrder> findRequest(long userId,String requestId) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ticket_order WHERE user_id=? AND request_id=?",ROW,userId,requestId).stream().findFirst();
    }
    public List<LockedOrder> list(long userId) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ticket_order WHERE user_id=? ORDER BY created_at DESC,id DESC",ROW,userId);
    }
    public List<OrderItemView> items(long orderId) {
        return jdbc.query("SELECT seat_id,row_no,seat_no,price_zone,unit_price FROM order_item WHERE order_id=? ORDER BY seat_id",
                (rs,n)->new OrderItemView(rs.getLong("seat_id"),rs.getString("row_no"),rs.getString("seat_no"),rs.getString("price_zone"),rs.getBigDecimal("unit_price")),orderId);
    }
    long insert(long userId,CreateOrderRequest request,String digest,BigDecimal amount,Instant now,Instant expiry) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO ticket_order(user_id,session_id,request_id,request_digest,selection_mode,status,total_amount,expire_at,created_at,updated_at) VALUES(?,?,?,?,?,'UNPAID',?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,userId); statement.setLong(2,request.sessionId()); statement.setString(3,request.requestId());
            statement.setString(4,digest); statement.setString(5,request.mode().name()); statement.setBigDecimal(6,amount);
            statement.setObject(7,utc(expiry)); statement.setObject(8,utc(now)); statement.setObject(9,utc(now)); return statement;
        },keys);
        return keys.getKey().longValue();
    }
    void item(long orderId,SessionSeat seat) {
        jdbc.update("INSERT INTO order_item(order_id,session_id,seat_id,row_no,seat_no,price_zone,unit_price) VALUES(?,?,?,?,?,?,?)",
                orderId,seat.sessionId(),seat.seatId(),seat.rowNo(),seat.seatNo(),seat.priceZone(),seat.price());
    }
    void setAmount(long orderId,BigDecimal amount) {
        if(jdbc.update("UPDATE ticket_order SET total_amount=? WHERE id=? AND status='UNPAID'",amount,orderId)!=1)
            throw new IllegalStateException("New order missing while finalizing price snapshot");
    }
    boolean hold(long sessionId,long seatId,long orderId,Instant expiry) {
        return jdbc.update("UPDATE session_seat SET status='HELD',owner_order_id=?,hold_expires_at=? WHERE session_id=? AND seat_id=? AND status='AVAILABLE'",orderId,utc(expiry),sessionId,seatId)==1;
    }
    int releaseHeld(LockedOrder order) {
        int released=0;
        for(var item:items(order.id())) {
            released+=jdbc.update("UPDATE session_seat SET status='AVAILABLE',owner_order_id=NULL,hold_expires_at=NULL WHERE session_id=? AND seat_id=? AND owner_order_id=? AND status='HELD'",order.sessionId(),item.seatId(),order.id());
        }
        return released;
    }
    void cancel(LockedOrder order,Instant now) {
        if(jdbc.update("UPDATE ticket_order SET status='CANCELLED',updated_at=? WHERE id=? AND status='UNPAID'",utc(now),order.id())!=1)
            throw new IllegalStateException("Locked order changed unexpectedly");
    }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Order lock requires a caller transaction");
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs,String name) throws SQLException { return rs.getObject(name,LocalDateTime.class).toInstant(ZoneOffset.UTC); }
}
