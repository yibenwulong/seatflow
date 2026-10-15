package io.github.yibenwulong.seatflow.payment;
import java.math.BigDecimal;
public record PaymentIntent(String merchantRequestNo, long orderId, BigDecimal amount) {}

