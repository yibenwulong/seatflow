package io.github.yibenwulong.seatflow.catalog;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import static org.assertj.core.api.Assertions.*;

final class SchemaAssertions {
    static void verify(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_session", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hall_seat", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat", Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE status = 'DISABLED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT block_id) FROM session_seat", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT price) FROM session_seat", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO session_seat(session_id,seat_id,row_no,seat_no,position,block_id,price_zone,x,y,price,status) SELECT session_id,seat_id,row_no,seat_no,position,block_id,price_zone,x,y,price,status FROM session_seat WHERE session_id=101 AND seat_id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE session_seat SET status='DISABLED' WHERE session_id=101 AND seat_id=1");
        assertThat(jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=102 AND seat_id=1", String.class)).isEqualTo("AVAILABLE");
        jdbc.update("UPDATE hall_seat SET row_no='CHANGED', price_zone='CHANGED' WHERE id=1");
        assertThat(jdbc.queryForObject("SELECT row_no FROM session_seat WHERE session_id=102 AND seat_id=1", String.class)).isEqualTo("A");
        verifyBusinessUniqueness(jdbc);
    }

    private static void verifyBusinessUniqueness(JdbcTemplate jdbc) {
        String orderColumns = "user_id,session_id,request_id,request_digest,selection_mode,status,total_amount,expire_at,created_at,updated_at";
        jdbc.update("INSERT INTO ticket_order(id," + orderColumns + ") VALUES(100,1,101,'request-1','abc','EXPLICIT','UNPAID',280,'2099-01-01 00:15:00','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO ticket_order(" + orderColumns + ") SELECT " + orderColumns + " FROM ticket_order WHERE id=100");
        // Same requestId for another user is valid.
        jdbc.update("INSERT INTO ticket_order(id," + orderColumns + ") VALUES(101,2,102,'request-1','abc','EXPLICIT','UNPAID',280,'2099-01-01 00:15:00','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "UPDATE session_seat SET status='HELD',owner_order_id=100,hold_expires_at='2099-01-01 00:15:00' WHERE session_id=102 AND seat_id=1");
        rejects(jdbc, "UPDATE session_seat SET status='HELD' WHERE session_id=102 AND seat_id=1");
        jdbc.update("INSERT INTO order_item(order_id,session_id,seat_id,row_no,seat_no,price_zone,unit_price) VALUES(100,101,2,'A','2','PREMIUM',280)");
        rejects(jdbc, "INSERT INTO order_item(order_id,session_id,seat_id,row_no,seat_no,price_zone,unit_price) VALUES(100,101,2,'A','2','PREMIUM',280)");
        rejects(jdbc, "INSERT INTO order_item(order_id,session_id,seat_id,row_no,seat_no,price_zone,unit_price) VALUES(100,102,3,'A','3','PREMIUM',280)");
        jdbc.update("INSERT INTO payment_intent(id,order_id,provider,merchant_request_no,amount,status,created_at,updated_at) VALUES(1,100,'MOCK','merchant-1',280,'CREATED','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO payment_intent(order_id,provider,merchant_request_no,amount,status,created_at,updated_at) VALUES(101,'MOCK','merchant-1',280,'CREATED','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO payment_record(payment_intent_id,order_id,provider,gateway_transaction_id,amount,status,received_at) VALUES(1,101,'MOCK','wrong-order',280,'RECEIVED','2099-01-01 00:00:00')");
        jdbc.update("INSERT INTO payment_record(id,payment_intent_id,order_id,provider,gateway_transaction_id,amount,status,received_at) VALUES(1,1,100,'MOCK','trade-1',280,'RECEIVED','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO payment_record(payment_intent_id,order_id,provider,gateway_transaction_id,amount,status,received_at) VALUES(1,100,'MOCK','trade-1',280,'RECEIVED','2099-01-01 00:00:00')");
        // Distinct second collection under the stable merchant intent must remain recordable.
        jdbc.update("INSERT INTO payment_record(id,payment_intent_id,order_id,provider,gateway_transaction_id,amount,status,received_at) VALUES(2,1,100,'MOCK','trade-2',280,'RECEIVED','2099-01-01 00:00:00')");
        jdbc.update("INSERT INTO refund_record(refund_no,order_id,payment_record_id,type,amount,status,created_at,updated_at) VALUES('refund-1',100,1,'NORMAL',280,'PENDING','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO refund_record(refund_no,order_id,payment_record_id,type,amount,status,created_at,updated_at) VALUES('wrong-order-refund',101,2,'NORMAL',280,'PENDING','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO refund_record(refund_no,order_id,payment_record_id,type,amount,status,created_at,updated_at) VALUES('refund-1',100,2,'DUPLICATE_PAYMENT',280,'PENDING','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        jdbc.update("INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,next_attempt_at,created_at) VALUES('event-1','ORDER_CREATED',100,'{}','PENDING','2099-01-01 00:00:00','2099-01-01 00:00:00')");
        rejects(jdbc, "INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,next_attempt_at,created_at) VALUES('event-1','ORDER_CREATED',100,'{}','PENDING','2099-01-01 00:00:00','2099-01-01 00:00:00')");
    }

    private static void rejects(JdbcTemplate jdbc, String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).satisfies(error -> {
            if (error instanceof DataIntegrityViolationException) return;
            // MySQL exposes CHECK violations as HY000/3819, outside Spring's
            // standard integrity SQLStates. Do not accept arbitrary SQL errors.
            assertThat(error).isInstanceOf(UncategorizedSQLException.class);
            assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode()).isEqualTo(3819);
        });
    }
}
