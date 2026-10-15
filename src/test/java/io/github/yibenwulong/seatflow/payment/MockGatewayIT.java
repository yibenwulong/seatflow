package io.github.yibenwulong.seatflow.payment;

import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class MockGatewayIT {
    @Test void successfulCollectionAndRefundSurviveCallerRollbackAndReplayHasOneMoneyEffect() {
        try (var db = MySqlTestDatabase.create()) {
            var jdbc = db.jdbc();
            var tm = new DataSourceTransactionManager(jdbc.getDataSource());
            var gateway = new MockPaymentGateway(jdbc, tm, Clock.systemUTC(), "test-secret");
            var intent = new PaymentIntent("merchant-1", 41L, new BigDecimal("280.00"));
            var payment = gateway.create(intent);
            assertThat(gateway.create(intent)).isEqualTo(payment);
            assertThatThrownBy(() -> new TransactionTemplate(tm).execute(status -> {
                gateway.complete(payment.transactionId());
                throw new IllegalStateException("booking rollback");
            })).isInstanceOf(IllegalStateException.class);
            var notification = gateway.notification(payment.transactionId());
            var verified = gateway.verifyNotification(notification);
            assertThat(verified.amount()).isEqualByComparingTo("280.00");
            var refund = new RefundInstruction("refund-1", payment.transactionId(), new BigDecimal("280.00"));
            assertThatThrownBy(() -> new TransactionTemplate(tm).execute(status -> {
                gateway.refund(refund);
                throw new IllegalStateException("local completion rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(gateway.refund(refund)).isEqualTo(gateway.refund(refund));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_refund", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> gateway.refund(new RefundInstruction("refund-1", payment.transactionId(), new BigDecimal("1.00")))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> gateway.refund(new RefundInstruction("refund-2", payment.transactionId(), new BigDecimal("280.00")))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void notificationRequiresSignatureAndActualSuccessfulMatchingFunds() {
        try (var db = MySqlTestDatabase.create()) {
            var jdbc = db.jdbc();
            var gateway = new MockPaymentGateway(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), Clock.systemUTC(), "test-secret");
            var payment = gateway.create(new PaymentIntent("merchant-2", 42L, new BigDecimal("180.00")));
            assertThatThrownBy(() -> gateway.verifyNotification(gateway.notification(payment.transactionId()))).isInstanceOf(RuntimeException.class);
            gateway.complete(payment.transactionId());
            var valid = gateway.notification(payment.transactionId());
            assertThat(gateway.verifyNotification(valid).merchantRequestNo()).isEqualTo("merchant-2");
            var forged = new java.util.HashMap<>(valid.fields());
            forged.put("amount", "0.01");
            assertThatThrownBy(() -> gateway.verifyNotification(new NotificationInput(forged))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> gateway.verifyNotification(new NotificationInput(Map.of("transaction_id", payment.transactionId())))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> gateway.create(new PaymentIntent("merchant-2", 42L, new BigDecimal("1.00")))).isInstanceOf(RuntimeException.class);
        }
    }
}
