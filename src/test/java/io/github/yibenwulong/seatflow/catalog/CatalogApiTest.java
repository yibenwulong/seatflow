package io.github.yibenwulong.seatflow.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.github.yibenwulong.seatflow.SeatFlowApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository repository;
    @Autowired Clock clock;

    @Test void mapsCompleteLayoutPriceAndNullableOwnershipFromTheSessionSnapshot() {
        var seats = repository.listSessionSeats(101);
        assertThat(seats).hasSize(16);
        SessionSeat first = seats.get(0);
        assertThat(first.sessionId()).isEqualTo(101);
        assertThat(first.seatId()).isEqualTo(1);
        assertThat(first.rowNo()).isEqualTo("A");
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.blockId()).isEqualTo("LEFT");
        assertThat(first.priceZone()).isEqualTo("PREMIUM");
        assertThat(first.price()).isEqualByComparingTo(new BigDecimal("280.00"));
        assertThat(first.ownerOrderId()).isNull();
        assertThat(first.holdExpiresAt()).isNull();
        assertThat(seats).filteredOn(s -> s.status().equals("DISABLED")).extracting(SessionSeat::seatId).containsExactly(10L);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test @Transactional
    void dateUsesUtcMidnightAndIncludesTheStartButExcludesTheNextDay() throws Exception {
        jdbc.update("INSERT INTO show_catalog(id,title,description,duration_minutes) VALUES(2,'Midnight Live','UTC boundary fixture',60)");
        jdbc.update("INSERT INTO show_session(id,show_id,hall_id,starts_at,sales_start_at,sales_end_at,status,layout_version) VALUES(103,2,1,'2099-12-01 00:00:00','2026-01-01 00:00:00','2099-12-01 00:00:00','ON_SALE',1)");
        mvc.perform(get("/api/v1/shows").param("date", "2099-12-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/shows").param("date", "2099-11-30"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void cityAndUtcDateFilterShowsWithoutDuplicatingTwoSessions() throws Exception {
        mvc.perform(get("/api/v1/shows").param("city", "Shanghai").param("date", "2099-12-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").isString()).andExpect(jsonPath("$[0].id").value("1")).andExpect(jsonPath("$[0].title").value("SeatFlow Live"));
        mvc.perform(get("/api/v1/shows").param("city", "Beijing")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/shows").param("date", "2099-12-03")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void listsSessionsInTimeOrderAndSerializesEveryLongIdAsString() throws Exception {
        mvc.perform(get("/api/v1/shows/1/sessions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].id").value("101"))
                .andExpect(jsonPath("$[0].id").isString()).andExpect(jsonPath("$[0].showId").isString()).andExpect(jsonPath("$[0].hallId").isString())
                .andExpect(jsonPath("$[0].showId").value("1")).andExpect(jsonPath("$[0].hallId").value("1"))
                .andExpect(jsonPath("$[0].startsAt").value("2099-12-01T11:00:00Z"));
        mvc.perform(get("/api/v1/sessions/101")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("101"));
    }

    @Test void missingResourcesAndInvalidDatesReturnStableErrorsWithRequestIds() throws Exception {
        mvc.perform(get("/api/v1/sessions/999").header("X-Request-Id", "catalog-check"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("catalog-check"));
        mvc.perform(get("/api/v1/shows/999/sessions")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shows").param("date", "not-a-date"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
