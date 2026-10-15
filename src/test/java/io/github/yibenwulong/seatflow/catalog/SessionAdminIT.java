package io.github.yibenwulong.seatflow.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yibenwulong.seatflow.SeatFlowApplication;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.util.Map;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL transaction checks, deliberately without an outer test transaction. */
@SpringBootTest(classes=SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SessionAdminIT {
    private static final MySqlTestDatabase DB=MySqlTestDatabase.create();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",DB::url);
        properties.add("spring.datasource.username",DB::user);
        properties.add("spring.datasource.password",DB::password);
    }
    @AfterAll static void dispose() { DB.close(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository repository;
    private boolean forcedFailureCheck;

    @AfterEach void cleanCreatedInventory() {
        if(forcedFailureCheck) jdbc.execute("ALTER TABLE session_seat DROP CHECK forced_snapshot_failure");
        jdbc.update("DELETE FROM session_seat WHERE session_id>102");
        jdbc.update("DELETE FROM show_session WHERE id>102");
        jdbc.update("UPDATE hall_seat SET row_no='A',seat_no='1',position=1,block_id='LEFT',price_zone='PREMIUM',x=1,y=1,enabled=true WHERE id=1");
        jdbc.update("UPDATE hall SET layout_version=1 WHERE id=1");
    }

    @Test void snapshotsFullPhysicalLayoutAndPricesOnRealMysqlAndPreservesItAfterHallEdits() throws Exception {
        var result=create(SessionAdminApiTest.valid()).andExpect(status().isCreated()).andReturn();
        long id=mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        var before=repository.listSessionSeats(id);
        assertThat(before).hasSize(16);
        assertThat(before).filteredOn(s -> s.status().equals("DISABLED")).extracting(SessionSeat::seatId).containsExactly(10L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_seat ss JOIN hall_seat hs ON hs.id=ss.seat_id WHERE ss.session_id=? AND ss.row_no=hs.row_no AND ss.seat_no=hs.seat_no AND ss.position=hs.position AND ss.block_id=hs.block_id AND ss.price_zone=hs.price_zone AND ss.x=hs.x AND ss.y=hs.y",Integer.class,id)).isEqualTo(16);
        assertThat(before).allSatisfy(s -> assertThat(s.price()).isEqualByComparingTo(s.priceZone().equals("PREMIUM")?"320.25":"200.50"));
        jdbc.update("UPDATE hall_seat SET row_no='EDIT',seat_no='EDIT',position=99,block_id='EDIT',price_zone='EDIT',x=99,y=99,enabled=false WHERE id=1");
        jdbc.update("UPDATE hall SET layout_version=2 WHERE id=1");
        assertThat(repository.listSessionSeats(id)).isEqualTo(before);
        assertThat(repository.findSession(id).orElseThrow().layoutVersion()).isEqualTo(1);
    }

    @Test void rollsBackSessionAndAlreadyInsertedSeatsWhenLaterSnapshotInsertFails() throws Exception {
        int sessions=count("show_session"), seats=count("session_seat");
        jdbc.execute("ALTER TABLE session_seat ADD CONSTRAINT forced_snapshot_failure CHECK (session_id<=102 OR seat_id<>2)");
        forcedFailureCheck=true;
        var result=create(SessionAdminApiTest.valid()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertThat(result.getResolvedException()).hasStackTraceContaining("forced_snapshot_failure");
        assertThat(count("show_session")).isEqualTo(sessions);
        assertThat(count("session_seat")).isEqualTo(seats);
        assertThat(repository.listSessionSeats(101)).hasSize(16);
    }

    @Test void invalidTimeAndIncompletePricesLeaveNoSessionOrSnapshotOnRealMysql() throws Exception {
        int sessions=count("show_session"), seats=count("session_seat");
        var body=SessionAdminApiTest.valid(); body.put("startsAt","2020-01-01T00:00:00Z");
        create(body).andExpect(status().isBadRequest());
        body=SessionAdminApiTest.valid(); body.put("prices",Map.of("PREMIUM",10));
        create(body).andExpect(status().isBadRequest());
        body=SessionAdminApiTest.valid(); body.put("prices",Map.of("PREMIUM",new BigDecimal("1.001"),"STANDARD",BigDecimal.TEN));
        create(body).andExpect(status().isBadRequest());
        body=SessionAdminApiTest.valid(); body.put("prices",Map.of("PREMIUM",new BigDecimal("10000000000.00"),"STANDARD",BigDecimal.TEN));
        create(body).andExpect(status().isBadRequest());
        body=SessionAdminApiTest.valid(); body.put("salesStartAt","2099-12-03T10:00:00.000000001Z"); body.put("salesEndAt","2099-12-03T10:00:00.000000002Z");
        create(body).andExpect(status().isBadRequest());
        body=SessionAdminApiTest.valid(); body.put("startsAt","+10000-01-01T00:00:00Z");
        create(body).andExpect(status().isBadRequest());
        body=SessionAdminApiTest.valid(); body.put("salesStartAt","0001-01-01T00:00:00Z");
        create(body).andExpect(status().isBadRequest());
        assertThat(count("show_session")).isEqualTo(sessions);
        assertThat(count("session_seat")).isEqualTo(seats);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private ResultActions create(Map<String,Object> body) throws Exception {
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username","admin","password","demo-seatflow"))))
                .andExpect(status().isOk()).andReturn();
        String token=mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        return mvc.perform(post("/api/v1/admin/sessions").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)));
    }
}
