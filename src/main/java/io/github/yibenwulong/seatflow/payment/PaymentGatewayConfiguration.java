package io.github.yibenwulong.seatflow.payment;

import com.alipay.api.DefaultAlipayClient;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class PaymentGatewayConfiguration {
    @Bean
    @ConditionalOnProperty(name="seatflow.payment.alipay.enabled",havingValue="true")
    AlipaySandboxGateway alipaySandboxGateway(Environment env) {
        String prefix="seatflow.payment.alipay.";
        String app=required(env,prefix+"app-id"), seller=required(env,prefix+"seller-id");
        String privateKey=required(env,prefix+"private-key"), publicKey=required(env,prefix+"public-key");
        String notify=required(env,prefix+"notify-url");
        if(!"https".equals(URI.create(notify).getScheme())) throw new IllegalStateException("Alipay notify URL must be HTTPS");
        // Fixed sandbox host prevents accidental live payments under a sandbox setting.
        var client=new DefaultAlipayClient("https://openapi-sandbox.dl.alipaydev.com/gateway.do",app,privateKey,"json","UTF-8",publicKey,"RSA2");
        return new AlipaySandboxGateway(client,app,seller,publicKey,notify);
    }
    private static String required(Environment env,String name) {
        String value=env.getProperty(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing "+name);
        return value;
    }
}
