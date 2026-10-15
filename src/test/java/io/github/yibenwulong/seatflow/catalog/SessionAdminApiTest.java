package io.github.yibenwulong.seatflow.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yibenwulong.seatflow.SeatFlowApplication;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fast MVC/H2 checks using real login tokens; MySQL transaction proof is separate. */
@SpringBootTest(classes=SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SessionAdminApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository repository;
    @Autowired SessionAdminService service;

    @Test void serviceItselfRejectsCallsWithoutTrustedAdminIdentity() {
        var request=mapper.convertValue(valid(),CreateSessionRequest.class);
        int sessions=count("show_session"), seats=count("session_seat");
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class)
                .satisfies(error -> assertThat(((io.github.yibenwulong.seatflow.common.ApiException)error).code()).isEqualTo("UNAUTHORIZED"));
        assertThat(count("show_session")).isEqualTo(sessions);
        assertThat(count("session_seat")).isEqualTo(seats);
    }

    @Test void unauthenticatedAndUserCannotCreateAnyInventory() throws Exception {
        int sessions=count("show_session"), seats=count("session_seat");
        mvc.perform(post("/api/v1/admin/sessions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(valid())))
                .andExpect(status().isUnauthorized());
        create(token("demo"),valid()).andExpect(status().isForbidden());
        assertThat(count("show_session")).isEqualTo(sessions);
        assertThat(count("session_seat")).isEqualTo(seats);
    }

    @Test void adminCreatesCompleteImmutableLayoutAndPriceSnapshotIncludingDisabledSeats() throws Exception {
        var response=create(token("admin"),valid()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString()).andExpect(jsonPath("$.showId").value("1"))
                .andExpect(jsonPath("$.hallId").value("1")).andExpect(jsonPath("$.layoutVersion").value(1))
                .andExpect(jsonPath("$.status").value("ON_SALE")).andReturn();
        long id=mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        var snapshot=repository.listSessionSeats(id);
        assertThat(snapshot).hasSize(16);
        assertThat(snapshot).filteredOn(s -> s.status().equals("DISABLED")).extracting(SessionSeat::seatId).containsExactly(10L);
        assertThat(snapshot).allSatisfy(s -> {
            assertThat(s.ownerOrderId()).isNull();
            assertThat(s.holdExpiresAt()).isNull();
            assertThat(s.price()).isEqualByComparingTo(s.priceZone().equals("PREMIUM")?"320.25":"200.50");
        });
        var first=snapshot.get(0);
        assertThat(first.seatId()).isEqualTo(1);
        assertThat(first.rowNo()).isEqualTo("A");
        assertThat(first.seatNo()).isEqualTo("1");
        assertThat(first.blockId()).isEqualTo("LEFT");
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.x()).isEqualByComparingTo("1.00");
        assertThat(first.y()).isEqualByComparingTo("1.00");
        jdbc.update("UPDATE hall_seat SET row_no='EDIT',seat_no='EDIT',position=99,block_id='EDIT',price_zone='EDIT',x=99,y=99,enabled=false WHERE id=1");
        jdbc.update("UPDATE hall SET layout_version=2 WHERE id=1");
        assertThat(repository.listSessionSeats(id)).isEqualTo(snapshot);
        assertThat(repository.findSession(id).orElseThrow().layoutVersion()).isEqualTo(1);
    }

    @Test void rejectsInvalidSaleAndStartBoundsWithoutCreatingAnyRows() throws Exception {
        String admin=token("admin");
        for (var change:List.of(Map.of("startsAt","2020-01-01T00:00:00Z"),
                Map.of("salesStartAt","2099-12-03T11:00:00Z"),Map.of("salesEndAt","2099-12-04T11:00:00Z"),
                Map.of("salesStartAt","2099-12-03T10:00:00Z","salesEndAt","2099-12-03T10:00:00Z"),
                Map.of("salesStartAt","2099-12-03T10:00:00.000000001Z","salesEndAt","2099-12-03T10:00:00.000000002Z"),
                Map.of("startsAt","+10000-01-01T00:00:00Z"), Map.of("salesStartAt","0001-01-01T00:00:00Z"))) {
            var body=valid(); body.putAll(change);
            assertRejectedWithoutRows(admin,body,400);
        }
    }

    @Test void rejectsMissingExtraNonpositiveAndUnsupportedMoneyPrecisionWithoutCreatingRows() throws Exception {
        String admin=token("admin");
        for(var prices:List.of(Map.of("PREMIUM",new BigDecimal("10")),
                Map.of("PREMIUM",new BigDecimal("10"),"STANDARD",new BigDecimal("20"),"EXTRA",new BigDecimal("10")),
                Map.of("PREMIUM",BigDecimal.ZERO,"STANDARD",BigDecimal.TEN),
                Map.of("PREMIUM",new BigDecimal("-1"),"STANDARD",BigDecimal.TEN),
                Map.of("PREMIUM",new BigDecimal("1.001"),"STANDARD",BigDecimal.TEN),
                Map.of("PREMIUM",new BigDecimal("10000000000.00"),"STANDARD",BigDecimal.TEN))) {
            var body=valid(); body.put("prices",prices);
            assertRejectedWithoutRows(admin,body,400);
        }
        var missing=valid(); missing.remove("prices");
        assertRejectedWithoutRows(admin,missing,400);
    }

    @Test void rejectsMissingShowOrHallAndEmptyOrEntirelyDisabledLayoutsWithoutRows() throws Exception {
        String admin=token("admin");
        var body=valid(); body.put("showId",999);
        assertRejectedWithoutRows(admin,body,404);
        body=valid(); body.put("hallId",999);
        assertRejectedWithoutRows(admin,body,404);
        jdbc.update("INSERT INTO hall(id,venue_id,name,layout_version) VALUES(900,1,'Empty test hall',1)");
        body=valid(); body.put("hallId",900);
        assertRejectedWithoutRows(admin,body,400);
        jdbc.update("UPDATE hall_seat SET enabled=false WHERE hall_id=1");
        assertRejectedWithoutRows(admin,valid(),400);
    }

    @Test void rejectsMalformedLayoutLabelsWithoutCreatingRows() throws Exception {
        String admin=token("admin");
        jdbc.update("UPDATE hall_seat SET block_id=' ' WHERE id=1");
        assertRejectedWithoutRows(admin,valid(),400);
    }

    private void assertRejectedWithoutRows(String token,Map<String,Object> body,int status) throws Exception {
        int sessions=count("show_session"), seats=count("session_seat");
        create(token,body).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(status==404?"NOT_FOUND":"INVALID_REQUEST"));
        assertThat(count("show_session")).isEqualTo(sessions);
        assertThat(count("session_seat")).isEqualTo(seats);
    }
    private org.springframework.test.web.servlet.ResultActions create(String token,Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/v1/admin/sessions").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)));
    }
    private String token(String username) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username",username,"password","demo-seatflow"))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).get("token").asText();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    static Map<String,Object> valid() {
        return new LinkedHashMap<>(Map.of("showId",1,"hallId",1,"startsAt","2099-12-03T11:00:00Z",
                "salesStartAt","2099-01-01T00:00:00Z","salesEndAt","2099-12-03T10:00:00Z",
                "prices",Map.of("PREMIUM",new BigDecimal("320.25"),"STANDARD",new BigDecimal("200.50"))));
    }
}
