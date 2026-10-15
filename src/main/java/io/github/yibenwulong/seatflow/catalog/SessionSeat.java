package io.github.yibenwulong.seatflow.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable session snapshot: physical hall edits never change this layout or price. */
public record SessionSeat(long sessionId, long seatId, String rowNo, String seatNo, int position,
                          String blockId, String priceZone, BigDecimal price, String status,
                          Long ownerOrderId, Instant holdExpiresAt, BigDecimal x, BigDecimal y) {}
