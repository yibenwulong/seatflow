package io.github.yibenwulong.seatflow.catalog;

import io.github.yibenwulong.seatflow.common.ApiException;
import io.github.yibenwulong.seatflow.identity.CurrentUser;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionAdminService {
    private static final Instant DATETIME_MIN=Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant DATETIME_END=Instant.parse("+10000-01-01T00:00:00Z");
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final Clock clock;
    public SessionAdminService(JdbcTemplate jdbc,CatalogRepository catalog,Clock clock) {
        this.jdbc=jdbc; this.catalog=catalog; this.clock=clock;
    }

    @Transactional
    public ShowSession create(CreateSessionRequest request) {
        CurrentUser.requireAdmin();
        if(request==null || request.showId()<=0 || request.hallId()<=0 || request.startsAt()==null
                || request.salesStartAt()==null || request.salesEndAt()==null || request.prices()==null) throw invalid("Missing session parameters");
        // Validate the values MySQL will store, rather than nanosecond bounds it cannot preserve.
        Instant starts=request.startsAt().truncatedTo(ChronoUnit.MICROS);
        Instant saleStart=request.salesStartAt().truncatedTo(ChronoUnit.MICROS);
        Instant saleEnd=request.salesEndAt().truncatedTo(ChronoUnit.MICROS);
        for(Instant value:List.of(starts,saleStart,saleEnd))
            if(value.isBefore(DATETIME_MIN) || !value.isBefore(DATETIME_END)) throw invalid("Session times must fit MySQL DATETIME range");
        if(!starts.isAfter(clock.instant()) || !saleStart.isBefore(saleEnd) || saleEnd.isAfter(starts))
            throw invalid("Require future start and salesStartAt < salesEndAt <= startsAt");
        if(!catalog.showExists(request.showId())) throw ApiException.notFound("Show");
        var versions=jdbc.query("SELECT layout_version FROM hall WHERE id=? FOR UPDATE",(rs,n)->rs.getInt(1),request.hallId());
        if(versions.isEmpty()) throw ApiException.notFound("Hall");
        // Lock the physical layout while copying it. Session reads never join HallSeat afterwards.
        List<HallSeatSnapshot> seats=jdbc.query("SELECT id,row_no,seat_no,position,block_id,price_zone,x,y,enabled FROM hall_seat WHERE hall_id=? ORDER BY id FOR UPDATE",
                (rs,n)->new HallSeatSnapshot(rs.getLong("id"),rs.getString("row_no"),rs.getString("seat_no"),rs.getInt("position"),
                        rs.getString("block_id"),rs.getString("price_zone"),rs.getBigDecimal("x"),rs.getBigDecimal("y"),rs.getBoolean("enabled")),request.hallId());
        if(seats.isEmpty() || seats.stream().noneMatch(HallSeatSnapshot::enabled)) throw invalid("Hall requires a sellable layout");
        Set<String> zones=new HashSet<>();
        for(var seat:seats) {
            if(seat.rowNo().isBlank() || seat.seatNo().isBlank() || seat.blockId().isBlank() || seat.priceZone().isBlank())
                throw invalid("Hall layout contains blank labels");
            zones.add(seat.priceZone());
        }
        if(!request.prices().keySet().equals(zones)) throw invalid("Prices must cover exactly the hall price zones");
        for(BigDecimal price:request.prices().values()) {
            if(!validPrice(price))
                throw invalid("Prices must be positive DECIMAL(12,2) amounts");
        }
        var key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO show_session(show_id,hall_id,starts_at,sales_start_at,sales_end_at,status,layout_version) VALUES(?,?,?,?,?,'ON_SALE',?)",Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,request.showId()); statement.setLong(2,request.hallId());
            statement.setObject(3,utc(starts)); statement.setObject(4,utc(saleStart)); statement.setObject(5,utc(saleEnd));
            statement.setInt(6,versions.get(0));
            return statement;
        },key);
        long sessionId=Objects.requireNonNull(key.getKey(),"Missing generated session id").longValue();
        for(var seat:seats) {
            jdbc.update("INSERT INTO session_seat(session_id,seat_id,row_no,seat_no,position,block_id,price_zone,x,y,price,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    sessionId,seat.id(),seat.rowNo(),seat.seatNo(),seat.position(),seat.blockId(),seat.priceZone(),seat.x(),seat.y(),
                    request.prices().get(seat.priceZone()),seat.enabled()?"AVAILABLE":"DISABLED");
        }
        return catalog.findSession(sessionId).orElseThrow(() -> new IllegalStateException("Created session missing"));
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    static boolean validPrice(BigDecimal price) {
        return price!=null && price.signum()>0 && price.scale()<=2 && price.precision()-(long)price.scale()<=10;
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message); }
    private record HallSeatSnapshot(long id,String rowNo,String seatNo,int position,String blockId,String priceZone,
                                    BigDecimal x,BigDecimal y,boolean enabled) {}
}
