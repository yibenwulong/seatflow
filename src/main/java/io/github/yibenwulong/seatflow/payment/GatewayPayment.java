package io.github.yibenwulong.seatflow.payment;
public record GatewayPayment(String provider, String transactionId, String checkoutUrl) {}
