package io.github.yibenwulong.seatflow.identity;

import io.github.yibenwulong.seatflow.SeatFlowApplication;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL + Spring MVC security boundary, isolated schema; never substitutes H2 or silently skips. */
@SpringBootTest(classes=SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthorizationApiTest.Probes.class)
class AuthorizationMySqlIT {
    private static final String SCHEMA = "seatflow_it_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String server = required("SEATFLOW_IT_MYSQL_URL");
        String user = required("SEATFLOW_IT_MYSQL_USER");
        String password = System.getenv().getOrDefault("SEATFLOW_IT_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(server, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA);
        String options = server.contains("?") ? server.substring(server.indexOf('?') + 1) + "&" : "";
        String url = server.substring(0, server.indexOf('/', "jdbc:mysql://".length()) + 1) + SCHEMA
                + "?" + options + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }

    @AfterAll static void cleanup() { if (admin != null) admin.execute("DROP DATABASE " + SCHEMA); }

    @Test void bcryptLoginAndHttpRoleBoundaryUseRealMigratedAccounts() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"demo\",\"password\":\"demo-seatflow\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value("1"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/v1/identity-probe")).andExpect(status().isUnauthorized());
        String token = auth.login("demo", "demo-seatflow").token();
        mvc.perform(get("/api/v1/identity-probe").param("userId", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("1"));
        mvc.perform(get("/api/v1/admin/identity-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/identity-probe").header("Authorization", "Bearer " + auth.login("admin", "demo-seatflow").token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("2"));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required; integration tests never silently skip");
        return value;
    }
}
