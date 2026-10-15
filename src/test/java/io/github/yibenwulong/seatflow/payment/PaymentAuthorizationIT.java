package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.SeatFlowApplication;
import io.github.yibenwulong.seatflow.identity.AuthService;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=SeatFlowApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="seatflow.payment.refund-worker-enabled=false")
@ActiveProfiles("test")
class PaymentAuthorizationIT {
    static final MySqlTestDatabase db=MySqlTestDatabase.create();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::url); p.add("spring.datasource.username",db::user); p.add("spring.datasource.password",db::password);
    }
    @AfterAll static void cleanup() { db.close(); }
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired ObjectMapper json;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Test void realHttpRoutesEnforceOwnerAndSignedNotifications() throws Exception {
        String owner=auth.login("demo","demo-seatflow").token(), other=auth.login("admin","demo-seatflow").token();
        var order=call("POST","/api/v1/orders",owner,"{\"requestId\":\"payment-http\",\"sessionId\":101,\"mode\":\"EXPLICIT\",\"seatIds\":[1]}",false);
        String orderId=json.readTree(order.body()).get("id").asText();
        String payments="/api/v1/orders/"+orderId+"/payments", refunds="/api/v1/orders/"+orderId+"/refunds";
        assertThat(call("POST",payments,null,null,false).statusCode()).isEqualTo(401);
        assertThat(call("POST",payments,other,null,false).statusCode()).isEqualTo(403);
        var created=call("POST",payments+"?userId=2",owner,null,false);
        assertThat(created.statusCode()).isEqualTo(200);
        var payment=json.readTree(created.body()); assertThat(payment.get("id").isTextual()).isTrue();
        String complete="/api/v1/dev/payments/"+payment.get("id").asText()+"/complete";
        assertThat(call("POST",complete,null,null,false).statusCode()).isEqualTo(401);
        assertThat(call("POST",complete,other,null,false).statusCode()).isEqualTo(403);
        String forged="transaction_id="+payment.get("transactionId").asText()+"&merchant_request_no="+payment.get("merchantRequestNo").asText()+"&amount=280.00&signature=forged";
        assertThat(call("POST","/api/v1/payments/mock/notify",null,forged,true).statusCode()).isEqualTo(400);
        assertThat(db.jdbc().queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,orderId)).isEqualTo("UNPAID");
        assertThat(call("POST",complete,owner,null,false).statusCode()).isEqualTo(200);
        assertThat(call("POST",complete,owner,null,false).statusCode()).isEqualTo(200);
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM payment_record",Long.class)).isEqualTo(1);
        assertThat(call("GET",refunds,null,null,false).statusCode()).isEqualTo(401);
        assertThat(call("POST",refunds,other,null,false).statusCode()).isEqualTo(403);
        assertThat(call("GET",refunds,other,null,false).statusCode()).isEqualTo(403);
        assertThat(call("POST",refunds,owner,null,false).statusCode()).isEqualTo(200);
        assertThat(json.readTree(call("GET",refunds,owner,null,false).body()).get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(call("POST","/api/v1/payments/alipay/notify",null,"x=y",true).statusCode()).isEqualTo(503);
    }
    HttpResponse<String> call(String method,String path,String token,String body,boolean form) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.header("Content-Type",form?"application/x-www-form-urlencoded":"application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
