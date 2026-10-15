package io.github.yibenwulong.seatflow.booking;

import java.math.BigDecimal;

public record OrderItemView(long seatId, String rowNo, String seatNo, String priceZone, BigDecimal unitPrice) {}
