package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.SeatFlowApplication;
import io.github.yibenwulong.seatflow.identity.AuthService;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual TCP HTTP requests through embedded Tomcat and the bearer filter, backed by real MySQL. */
@SpringBootTest(classes=SeatFlowApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderAuthorizationIT {
    static final MySqlTestDatabase db=MySqlTestDatabase.create();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::url); p.add("spring.datasource.username",db::user);
        p.add("spring.datasource.password",db::password);
    }
    @AfterAll static void cleanup() { db.close(); }
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired ObjectMapper json;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test void actualOrderRoutesUseTrustedIdentityAndNeverLeakOtherUsersOrders() throws Exception {
        for(String path:new String[]{"/api/v1/orders","/api/v1/orders/1"})
            assertThat(call("GET",path,null,null).statusCode()).isEqualTo(401);
        assertThat(call("POST","/api/v1/orders",null,"{}").statusCode()).isEqualTo(401);
        assertThat(call("POST","/api/v1/orders/1/cancel",null,null).statusCode()).isEqualTo(401);
        String owner=auth.login("demo","demo-seatflow").token();
        String other=auth.login("admin","demo-seatflow").token();
        var created=call("POST","/api/v1/orders",owner,
                "{\"requestId\":\"http-owner\",\"sessionId\":101,\"mode\":\"EXPLICIT\",\"seatIds\":[1],\"userId\":2}");
        assertThat(created.statusCode()).isEqualTo(200);
        var body=json.readTree(created.body()); assertThat(body.get("id").isTextual()).isTrue();
        String id=body.get("id").asText();
        assertThat(db.jdbc().queryForObject("SELECT user_id FROM ticket_order WHERE id=?",Long.class,id)).isEqualTo(1L);
        assertThat(call("GET","/api/v1/orders/"+id,owner,null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/v1/orders/"+id,other,null).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/v1/orders/"+id+"/cancel",other,null).statusCode()).isEqualTo(403);
        var otherList=call("GET","/api/v1/orders?userId=1",other,null);
        assertThat(otherList.statusCode()).isEqualTo(200); assertThat(json.readTree(otherList.body()).size()).isZero();
        var ownList=call("GET","/api/v1/orders?userId=2",owner,null);
        assertThat(ownList.statusCode()).isEqualTo(200); assertThat(json.readTree(ownList.body()).size()).isEqualTo(1);
        var cancel=call("POST","/api/v1/orders/"+id+"/cancel",owner,null);
        assertThat(cancel.statusCode()).isEqualTo(200); assertThat(json.readTree(cancel.body()).get("status").asText()).isEqualTo("CANCELLED");
        var malformed=call("POST","/api/v1/orders",owner,"{\"requestId\":\"invalid\",\"sessionId\":101,\"mode\":\"EXPLICIT\",\"seatIds\":[1,1]}");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json.readTree(malformed.body()).get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(json.readTree(malformed.body()).get("requestId").asText()).isNotBlank();
    }
    private HttpResponse<String> call(String method,String path,String token,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
