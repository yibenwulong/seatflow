package io.github.yibenwulong.seatflow.seating;

import java.math.BigDecimal;

/** Public physical seat snapshot; order ownership is intentionally private. */
public record SeatView(long seatId, String rowNo, String seatNo, int position, String blockId,
                       String priceZone, BigDecimal price, String status, BigDecimal x, BigDecimal y) {}
