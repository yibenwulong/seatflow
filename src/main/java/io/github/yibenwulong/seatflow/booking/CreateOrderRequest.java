package io.github.yibenwulong.seatflow.booking;

import java.util.List;

public record CreateOrderRequest(String requestId, long sessionId, SelectionMode mode,
                                 List<Long> seatIds, Integer count, String priceZone) {}
