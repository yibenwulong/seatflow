package io.github.yibenwulong.seatflow.payment;

import com.alipay.api.*;
import com.alipay.api.domain.*;
import com.alipay.api.request.*;
import com.alipay.api.internal.util.AlipaySignature;
import io.github.yibenwulong.seatflow.common.ApiException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/** Optional official SDK adapter; no live sandbox capability is claimed by unit tests. */
public final class AlipaySandboxGateway implements PaymentGateway {
    private final AlipayClient client;
    private final String appId, sellerId, publicKey, notifyUrl;
    public AlipaySandboxGateway(AlipayClient client,String appId,String sellerId,String publicKey,String notifyUrl) {
        this.client=client; this.appId=appId; this.sellerId=sellerId; this.publicKey=publicKey; this.notifyUrl=notifyUrl;
    }
    @Override public String provider() { return "alipay"; }
    @Override public GatewayPayment create(PaymentIntent intent) {
        var model=new AlipayTradePrecreateModel();
        model.setOutTradeNo(intent.merchantRequestNo()); model.setTotalAmount(intent.amount().toPlainString());
        model.setSubject("SeatFlow order "+intent.orderId());
        var request=new AlipayTradePrecreateRequest(); request.setBizModel(model); request.setNotifyUrl(notifyUrl);
        try {
            var response=client.execute(request);
            if(!"10000".equals(response.getCode()) || !response.isSuccess() || !intent.merchantRequestNo().equals(response.getOutTradeNo()) || response.getQrCode()==null) throw unavailable();
            return new GatewayPayment(provider(),null,response.getQrCode());
        } catch(AlipayApiException ex) { throw unavailable(); }
    }
    @Override public VerifiedPayment verifyNotification(NotificationInput input) {
        var f=input.fields();
        try {
            if(!"RSA2".equals(f.get("sign_type")) || !AlipaySignature.rsaCheckV1(new java.util.HashMap<>(f),publicKey,"UTF-8","RSA2")
                    || !appId.equals(f.get("app_id")) || !sellerId.equals(f.get("seller_id"))
                    || !java.util.Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(f.getOrDefault("trade_status",""))) throw invalid();
            String merchant=f.get("out_trade_no"), txn=f.get("trade_no");
            BigDecimal amount=new BigDecimal(f.getOrDefault("total_amount","0"));
            if(merchant==null || merchant.isBlank() || txn==null || txn.isBlank() || amount.signum()<=0 || amount.scale()>2) throw invalid();
            return new VerifiedPayment(provider(),merchant,txn,amount);
        } catch(AlipayApiException | IllegalArgumentException ex) { throw invalid(); }
    }
    @Override public GatewayRefund refund(RefundInstruction instruction) {
        var model=new AlipayTradeRefundModel();
        model.setTradeNo(instruction.transactionId()); model.setOutRequestNo(instruction.refundNo());
        model.setRefundAmount(instruction.amount().toPlainString());
        var request=new AlipayTradeRefundRequest(); request.setBizModel(model);
        try {
            // Alipay guarantees replay with the same out_request_no has one money effect.
            var response=client.execute(request);
            if(!"10000".equals(response.getCode()) || !response.isSuccess() || !instruction.transactionId().equals(response.getTradeNo())
                    || response.getRefundFee()==null || new BigDecimal(response.getRefundFee()).compareTo(instruction.amount())!=0) throw unavailable();
            // This API has no distinct refund transaction ID. Keep a bounded,
            // deterministic reference to the transaction/request pair.
            String reference="alipay-"+java.util.UUID.nameUUIDFromBytes(
                    (instruction.transactionId()+"\n"+instruction.refundNo()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new GatewayRefund(instruction.refundNo(),reference);
        } catch(AlipayApiException | NumberFormatException ex) { throw unavailable(); }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NOTIFICATION","Invalid payment notification"); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"GATEWAY_UNAVAILABLE","Sandbox gateway request failed"); }
}
