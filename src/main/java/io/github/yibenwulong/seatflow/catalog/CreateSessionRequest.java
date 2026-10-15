package io.github.yibenwulong.seatflow.catalog;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record CreateSessionRequest(@Positive long showId, @Positive long hallId,
                                   @NotNull Instant startsAt, @NotNull Instant salesStartAt,
                                   @NotNull Instant salesEndAt, @NotEmpty Map<String,BigDecimal> prices) {}
