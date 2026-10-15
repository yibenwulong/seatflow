package io.github.yibenwulong.seatflow.payment;
import java.math.BigDecimal;
import java.time.Instant;
public record RefundView(long id,long orderId,String refundNo,String type,BigDecimal amount,String status,int attempts,Instant nextAttemptAt,String lastError) {}
