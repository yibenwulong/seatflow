package io.github.yibenwulong.seatflow.seating;

import io.github.yibenwulong.seatflow.SeatFlowApplication;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fast MVC/H2 compatibility test. Real Redis/MySQL checks live in SeatMapCacheIT. */
@SpringBootTest(classes=SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeatMapApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean SeatMapCache cache;
    @BeforeEach void cacheMiss() { when(cache.get(anyLong())).thenReturn(Optional.empty()); }
    @Test void publicMapContainsPhysicalStringIdsFullSnapshotAndAsOfWithoutOwnerDetails() throws Exception {
        mvc.perform(get("/api/v1/sessions/102/seats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("102")).andExpect(jsonPath("$.layoutVersion").value(1))
                .andExpect(jsonPath("$.asOf").isNotEmpty()).andExpect(jsonPath("$.seats.length()").value(16))
                .andExpect(jsonPath("$.seats[0].seatId").value("1")).andExpect(jsonPath("$.seats[0].seatId").isString())
                .andExpect(jsonPath("$.seats[0].rowNo").value("A")).andExpect(jsonPath("$.seats[0].seatNo").value("1"))
                .andExpect(jsonPath("$.seats[0].price").value(280)).andExpect(jsonPath("$.seats[0].x").value(1))
                .andExpect(jsonPath("$.seats[0].y").value(1)).andExpect(jsonPath("$.seats[9].status").value("DISABLED"))
                .andExpect(jsonPath("$.seats[0].ownerOrderId").doesNotExist()).andExpect(jsonPath("$.seats[0].holdExpiresAt").doesNotExist());
    }
    @Test void unknownSessionReturns404() throws Exception {
        mvc.perform(get("/api/v1/sessions/999/seats")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
