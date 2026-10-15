package io.github.yibenwulong.seatflow.seating;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdjacentSeatAllocatorTest {
    private final AdjacentSeatAllocator allocator = new AdjacentSeatAllocator();

    @Test void returnsAllTwoThreeAndFourSeatWindowsInStablePhysicalOrder() {
        var seats = new ArrayList<>(List.of(seat(14,4), seat(11,1), seat(13,3), seat(12,2)));
        assertThat(allocator.candidates(seats,2,null)).containsExactly(List.of(11L,12L),List.of(12L,13L),List.of(13L,14L));
        assertThat(allocator.candidates(seats,3,null)).containsExactly(List.of(11L,12L,13L),List.of(12L,13L,14L));
        assertThat(allocator.candidates(seats,4,null)).containsExactly(List.of(11L,12L,13L,14L));
        Collections.reverse(seats);
        assertThat(allocator.candidates(seats,4,"P")).containsExactly(List.of(11L,12L,13L,14L));
    }

    @Test void neverCrossesAislesRowsPriceZonesMissingDisabledOrOccupiedSeats() {
        for (String boundary : List.of("ROW", "BLOCK", "ZONE", "GAP", "DISABLED", "HELD", "SOLD")) {
            SeatView right = new SeatView(2, boundary.equals("ROW")?"B":"A", "2", boundary.equals("GAP")?3:2,
                    boundary.equals("BLOCK")?"R":"L", boundary.equals("ZONE")?"S":"P", BigDecimal.TEN,
                    List.of("DISABLED","HELD","SOLD").contains(boundary)?boundary:"AVAILABLE", BigDecimal.ONE, BigDecimal.ZERO);
            assertThat(allocator.candidates(List.of(seat(1,1),right),2,null)).as(boundary).isEmpty();
        }
        assertThat(allocator.candidates(List.of(seat(1,1),seat(2,2)),2,"S")).isEmpty();
        assertThat(allocator.candidates(List.of(),2,null)).isEmpty();
    }

    @Test void rejectsCountsOutsideTwoThroughFour() {
        for (int count : new int[]{Integer.MIN_VALUE,-1,0,1,5,Integer.MAX_VALUE})
            assertThatIllegalArgumentException().isThrownBy(() -> allocator.candidates(List.of(),count,null));
    }

    @Test void exhaustiveSmallLayoutsMatchAnIndependentCombinationOracle() {
        // Five positions encode missing seats, occupied seats, two rows, an aisle, and price zones.
        List<SeatView> layout = List.of(seat(50,1),seat(20,2),seat(90,3),
                new SeatView(3,"A","4",4,"R","P",BigDecimal.TEN,"AVAILABLE",BigDecimal.ZERO,BigDecimal.ZERO),
                new SeatView(4,"B","1",1,"L","S",BigDecimal.TEN,"AVAILABLE",BigDecimal.ZERO,BigDecimal.ZERO));
        for (int states=0; states<243; states++) {
            int encoded=states;
            List<SeatView> seats=new ArrayList<>();
            for (SeatView original:layout) {
                int state=encoded%3; encoded/=3;
                if(state!=0) seats.add(new SeatView(original.seatId(),original.rowNo(),original.seatNo(),original.position(),
                        original.blockId(),original.priceZone(),original.price(),state==1?"AVAILABLE":"HELD",original.x(),original.y()));
            }
            Collections.reverse(seats);
            for(int count=2;count<=4;count++) for(String zone:new String[]{null,"P","S"}) {
                Set<List<Long>> expected=new HashSet<>();
                for(int subset=0;subset<(1<<seats.size());subset++) {
                    if(Integer.bitCount(subset)!=count) continue;
                    List<SeatView> chosen=new ArrayList<>();
                    for(int i=0;i<seats.size();i++) if((subset&(1<<i))!=0) chosen.add(seats.get(i));
                    chosen.sort(Comparator.comparingInt(SeatView::position));
                    SeatView first=chosen.get(0);
                    boolean legal=true;
                    for(int i=0;i<chosen.size();i++) {
                        SeatView s=chosen.get(i);
                        legal &= s.status().equals("AVAILABLE") && s.rowNo().equals(first.rowNo()) && s.blockId().equals(first.blockId())
                                && s.priceZone().equals(first.priceZone()) && s.position()==first.position()+i
                                && (zone==null || zone.equals(s.priceZone()));
                    }
                    if(legal) expected.add(chosen.stream().map(SeatView::seatId).toList());
                }
                assertThat(allocator.candidates(seats,count,zone)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    private static SeatView seat(long id,int position) {
        return new SeatView(id,"A",String.valueOf(position),position,"L","P",BigDecimal.TEN,"AVAILABLE",BigDecimal.ZERO,BigDecimal.ZERO);
    }
}
