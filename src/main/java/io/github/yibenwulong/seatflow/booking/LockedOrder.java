package io.github.yibenwulong.seatflow.booking;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable row snapshot returned by lockOrder; the database lock lasts until caller commit. */
public record LockedOrder(long id, long userId, long sessionId, String requestId, String requestDigest,
                          SelectionMode selectionMode, String status, BigDecimal totalAmount,
                          Instant expireAt, Instant createdAt, Instant updatedAt) {}
