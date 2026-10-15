package io.github.yibenwulong.seatflow.payment;
import java.math.BigDecimal;
public record PaymentView(long id,long orderId,String provider,String merchantRequestNo,String transactionId,BigDecimal amount,String checkoutUrl) {}
