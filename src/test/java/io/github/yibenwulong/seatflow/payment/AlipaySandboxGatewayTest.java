package io.github.yibenwulong.seatflow.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlipaySandboxGatewayTest {
    @Test void refundReferenceFitsLedgerForMaximumLengthAlipayTransactionNumber() throws Exception {
        var client=mock(AlipayClient.class);
        String transaction="t".repeat(64);
        var response=new AlipayTradeRefundResponse(); response.setCode("10000"); response.setTradeNo(transaction); response.setRefundFee("280.00");
        when(client.execute(any(AlipayTradeRefundRequest.class))).thenReturn(response);
        var gateway=new AlipaySandboxGateway(client,"app","seller","key","https://merchant.example/notify");
        var instruction=new RefundInstruction("refund-"+UUID.randomUUID(),transaction,new BigDecimal("280.00"));
        var result=gateway.refund(instruction);
        assertThat(result.gatewayRefundId().length()).isLessThanOrEqualTo(100);
        assertThat(gateway.refund(instruction)).isEqualTo(result);
    }
    @Test void disabledSandboxNeedsNoSecretsAndEnabledSandboxRequiresAllConfiguration() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(PaymentGatewayConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(AlipaySandboxGateway.class));
        runner.withPropertyValues("seatflow.payment.alipay.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void createUsesServerAmountStableMerchantAndVerifiedSuccessResponse() throws Exception {
        var client=mock(AlipayClient.class);
        var response=new com.alipay.api.response.AlipayTradePrecreateResponse();
        response.setCode("10000"); response.setOutTradeNo("pay-1"); response.setQrCode("sandbox-qr");
        when(client.execute(any(com.alipay.api.request.AlipayTradePrecreateRequest.class))).thenReturn(response);
        var gateway=new AlipaySandboxGateway(client,"app","seller","key","https://merchant.example/notify");
        assertThat(gateway.create(new PaymentIntent("pay-1",21,new BigDecimal("280.00"))).checkoutUrl()).isEqualTo("sandbox-qr");
        var captor=org.mockito.ArgumentCaptor.forClass(com.alipay.api.request.AlipayTradePrecreateRequest.class);
        verify(client).execute(captor.capture());
        var model=(com.alipay.api.domain.AlipayTradePrecreateModel)captor.getValue().getBizModel();
        assertThat(model.getOutTradeNo()).isEqualTo("pay-1");
        assertThat(model.getTotalAmount()).isEqualTo("280.00");
        response.setOutTradeNo("other");
        assertThatThrownBy(()->gateway.create(new PaymentIntent("pay-1",21,new BigDecimal("280.00")))).isInstanceOf(RuntimeException.class);
    }
    @Test void verifiesRealRsaSignatureMerchantAndSuccessfulStatus() throws Exception {
        var gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); var keys=gen.generateKeyPair();
        String publicKey=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        String privateKey=Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        var gateway=new AlipaySandboxGateway(mock(AlipayClient.class),"app-1","seller-1",publicKey,"https://merchant.example/notify");
        var fields=new TreeMap<String,String>(Map.of("app_id","app-1","seller_id","seller-1","trade_status","TRADE_SUCCESS","out_trade_no","pay-1","trade_no","trade-1","total_amount","280.00","sign_type","RSA2"));
        sign(fields,privateKey);
        assertThat(gateway.verifyNotification(new NotificationInput(fields)).amount()).isEqualByComparingTo("280.00");
        fields.put("total_amount","1.00");
        assertThatThrownBy(()->gateway.verifyNotification(new NotificationInput(fields))).isInstanceOf(RuntimeException.class);
        fields.put("seller_id","other-seller"); sign(fields,privateKey);
        assertThatThrownBy(()->gateway.verifyNotification(new NotificationInput(fields))).isInstanceOf(RuntimeException.class);
        fields.put("seller_id","seller-1"); fields.put("trade_status","WAIT_BUYER_PAY"); sign(fields,privateKey);
        assertThatThrownBy(()->gateway.verifyNotification(new NotificationInput(fields))).isInstanceOf(RuntimeException.class);
    }
    @Test void refundAlwaysUsesStableRequestNumberAndChecksGatewaySuccess() throws Exception {
        var client=mock(AlipayClient.class);
        var response=new AlipayTradeRefundResponse(); response.setCode("10000"); response.setTradeNo("txn-1"); response.setRefundFee("280.00");
        when(client.execute(any(AlipayTradeRefundRequest.class))).thenReturn(response);
        var gateway=new AlipaySandboxGateway(client,"app","seller","key","https://merchant.example/notify");
        var instruction=new RefundInstruction("stable-refund","txn-1",new BigDecimal("280.00"));
        gateway.refund(instruction); gateway.refund(instruction);
        var captor=org.mockito.ArgumentCaptor.forClass(AlipayTradeRefundRequest.class);
        verify(client,times(2)).execute(captor.capture());
        for(var request:captor.getAllValues()) {
            var model=(com.alipay.api.domain.AlipayTradeRefundModel)request.getBizModel();
            assertThat(model.getOutRequestNo()).isEqualTo("stable-refund");
            assertThat(model.getTradeNo()).isEqualTo("txn-1");
        }
        response.setCode("40004");
        assertThatThrownBy(()->gateway.refund(instruction)).isInstanceOf(RuntimeException.class);
    }
    private static void sign(Map<String,String> fields,String privateKey) throws Exception {
        fields.remove("sign");
        var canonical=new TreeMap<>(fields); canonical.remove("sign_type");
        fields.put("sign",AlipaySignature.rsaSign(AlipaySignature.getSignContent(canonical),privateKey,"UTF-8","RSA2"));
    }
}
