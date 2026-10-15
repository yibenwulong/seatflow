package io.github.yibenwulong.seatflow.payment;
import java.math.BigDecimal;
/** Trusted adapter output only; never bind HTTP bodies to this type. */
public record VerifiedPayment(String provider, String merchantRequestNo, String transactionId, BigDecimal amount) {}
