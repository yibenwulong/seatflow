package io.github.yibenwulong.seatflow.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderView(long id, long sessionId, String requestId, SelectionMode mode, String status,
                        BigDecimal totalAmount, Instant expireAt, Instant createdAt, List<OrderItemView> items) {
    public OrderView { items=List.copyOf(items); }
}
