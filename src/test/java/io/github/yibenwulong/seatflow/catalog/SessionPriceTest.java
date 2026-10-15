package io.github.yibenwulong.seatflow.catalog;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionPriceTest {
    @Test void rejectsHugeExponentsWithoutIntegerOverflowBeforeAnyDatabaseBinding() {
        assertThat(SessionAdminService.validPrice(new BigDecimal("1E+2147483647"))).isFalse();
        assertThat(SessionAdminService.validPrice(new BigDecimal("9999999999.99"))).isTrue();
        assertThat(SessionAdminService.validPrice(new BigDecimal("0.01"))).isTrue();
    }
}
