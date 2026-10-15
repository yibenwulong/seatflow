package io.github.yibenwulong.seatflow.seating;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AdjacentSeatAllocator {
    public List<List<Long>> candidates(List<SeatView> seats, int count, String priceZone) {
        if (count < 2 || count > 4) throw new IllegalArgumentException("Adjacent count must be between 2 and 4");
        List<SeatView> ordered = seats.stream()
                .filter(s -> "AVAILABLE".equals(s.status()) && (priceZone == null || priceZone.equals(s.priceZone())))
                .sorted(Comparator.comparing(SeatView::rowNo).thenComparing(SeatView::blockId)
                        .thenComparing(SeatView::priceZone).thenComparingInt(SeatView::position).thenComparingLong(SeatView::seatId))
                .toList();
        List<List<Long>> result = new ArrayList<>();
        for (int start = 0; start <= ordered.size() - count; start++) {
            SeatView first = ordered.get(start);
            boolean legal = true;
            for (int offset = 1; offset < count; offset++) {
                SeatView next = ordered.get(start + offset);
                if (!first.rowNo().equals(next.rowNo()) || !first.blockId().equals(next.blockId())
                        || !first.priceZone().equals(next.priceZone()) || next.position() != first.position() + offset) {
                    legal = false;
                    break;
                }
            }
            if (legal) result.add(ordered.subList(start, start + count).stream().map(SeatView::seatId).toList());
        }
        return List.copyOf(result);
    }
}
