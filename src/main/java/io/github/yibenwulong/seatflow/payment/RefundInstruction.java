package io.github.yibenwulong.seatflow.payment;
import java.math.BigDecimal;
public record RefundInstruction(String refundNo, String transactionId, BigDecimal amount) {}

