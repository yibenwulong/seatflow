package io.github.yibenwulong.seatflow.catalog;

import java.time.Instant;

public record ShowSession(long id, long showId, long hallId, Instant startsAt,
                          Instant salesStartAt, Instant salesEndAt, String status, int layoutVersion) {}
