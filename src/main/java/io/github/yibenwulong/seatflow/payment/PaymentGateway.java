package io.github.yibenwulong.seatflow.payment;
public interface PaymentGateway {
    String provider();
    GatewayPayment create(PaymentIntent intent);
    VerifiedPayment verifyNotification(NotificationInput input);
    GatewayRefund refund(RefundInstruction instruction);
}

