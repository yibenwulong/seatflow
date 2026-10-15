package io.github.yibenwulong.seatflow.payment;

import io.github.yibenwulong.seatflow.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Local simulated external ledger. Its commits are deliberately independent of booking transactions. */
@Component
public class MockPaymentGateway implements PaymentGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate external;
    private final Clock clock;
    private final String secret;

    @Autowired
    public MockPaymentGateway(JdbcTemplate jdbc, PlatformTransactionManager tm, Clock clock,
            @Value("${seatflow.payment.mock-secret:}") String secret) {
        this.jdbc=jdbc; this.clock=clock;
        this.secret=secret.isBlank()?UUID.randomUUID().toString():secret;
        external=new TransactionTemplate(tm);
        external.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public String provider() { return "mock"; }
    @Override public GatewayPayment create(PaymentIntent intent) {
        positive(intent.amount());
        String txn="mock-"+UUID.nameUUIDFromBytes(intent.merchantRequestNo().getBytes(StandardCharsets.UTF_8));
        return external.execute(status -> {
            jdbc.update("INSERT INTO mock_gateway_payment(gateway_transaction_id,merchant_request_no,amount,status,created_at) VALUES(?,?,?,'PENDING',?) ON DUPLICATE KEY UPDATE gateway_transaction_id=gateway_transaction_id",
                    txn,intent.merchantRequestNo(),intent.amount(),now());
            var fact=payment(txn,true);
            if(!fact.merchant().equals(intent.merchantRequestNo()) || fact.amount().compareTo(intent.amount())!=0) throw conflict("Payment request changed");
            return new GatewayPayment(provider(),txn,null);
        });
    }
    /** Only the authenticated local/test controller may expose completion. */
    public VerifiedPayment complete(String txn) {
        return external.execute(status -> {
            var fact=payment(txn,true);
            jdbc.update("UPDATE mock_gateway_payment SET status='SUCCESS' WHERE gateway_transaction_id=?",txn);
            return new VerifiedPayment(provider(),fact.merchant(),txn,fact.amount());
        });
    }
    @Override public VerifiedPayment verifyNotification(NotificationInput input) {
        var fields=input.fields();
        String txn=fields.get("transaction_id"), merchant=fields.get("merchant_request_no"), amount=fields.get("amount"), signature=fields.get("signature");
        if(txn==null || merchant==null || amount==null || signature==null
                || !MessageDigest.isEqual(sign(merchant,txn,amount).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8)))
            throw invalid();
        return external.execute(status -> {
            var fact=payment(txn,false);
            if(!fact.status().equals("SUCCESS") || !fact.merchant().equals(merchant) || !fact.amount().toPlainString().equals(amount)) throw invalid();
            return new VerifiedPayment(provider(),merchant,txn,fact.amount());
        });
    }
    /** Internal signing utility: public notification clients cannot manufacture funds. */
    NotificationInput notification(String txn) {
        return external.execute(status -> {
            var fact=payment(txn,false);
            String amount=fact.amount().toPlainString();
            return new NotificationInput(Map.of("transaction_id",txn,"merchant_request_no",fact.merchant(),"amount",amount,"signature",sign(fact.merchant(),txn,amount)));
        });
    }
    @Override public GatewayRefund refund(RefundInstruction instruction) {
        positive(instruction.amount());
        return external.execute(status -> {
            var fact=payment(instruction.transactionId(),true);
            if(!fact.status().equals("SUCCESS")) throw conflict("Collection has not succeeded");
            var prior=jdbc.query("SELECT * FROM mock_gateway_refund WHERE refund_no=?",(rs,n) -> new RefundFact(rs.getString("gateway_transaction_id"),rs.getBigDecimal("amount"),rs.getString("gateway_refund_id")),instruction.refundNo());
            if(!prior.isEmpty()) {
                var p=prior.get(0);
                if(!p.txn().equals(instruction.transactionId()) || p.amount().compareTo(instruction.amount())!=0) throw conflict("Refund request changed");
                return new GatewayRefund(instruction.refundNo(),p.id());
            }
            BigDecimal refunded=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM mock_gateway_refund WHERE gateway_transaction_id=?",BigDecimal.class,instruction.transactionId());
            if(refunded.add(instruction.amount()).compareTo(fact.amount())>0) throw conflict("Refund exceeds collection");
            String id="mock-refund-"+UUID.nameUUIDFromBytes(instruction.refundNo().getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO mock_gateway_refund(refund_no,gateway_transaction_id,gateway_refund_id,amount,status,created_at) VALUES(?,?,?,?,'SUCCESS',?)",
                    instruction.refundNo(),instruction.transactionId(),id,instruction.amount(),now());
            return new GatewayRefund(instruction.refundNo(),id);
        });
    }
    private Fact payment(String txn, boolean lock) {
        return jdbc.query("SELECT * FROM mock_gateway_payment WHERE gateway_transaction_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Fact(rs.getString("merchant_request_no"),rs.getBigDecimal("amount"),rs.getString("status")),txn)
                .stream().findFirst().orElseThrow(MockPaymentGateway::invalid);
    }
    private String sign(String merchant,String txn,String amount) {
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((merchant+"\n"+txn+"\n"+amount).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    private static void positive(BigDecimal amount) { if(amount==null || amount.signum()<=0 || amount.scale()>2) throw conflict("Invalid amount"); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"GATEWAY_CONFLICT",message); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NOTIFICATION","Invalid payment notification"); }
    private record Fact(String merchant,BigDecimal amount,String status) {}
    private record RefundFact(String txn,BigDecimal amount,String id) {}
}

