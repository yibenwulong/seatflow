package io.github.yibenwulong.seatflow.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;
    public CatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Show> listShows(String city, LocalDate date) {
        StringBuilder sql = new StringBuilder("SELECT sh.id, sh.title, sh.description, sh.duration_minutes FROM show_catalog sh WHERE EXISTS (SELECT 1 FROM show_session ss JOIN hall h ON h.id=ss.hall_id JOIN venue v ON v.id=h.venue_id WHERE ss.show_id=sh.id");
        List<Object> args = new ArrayList<>();
        if (city != null && !city.isBlank()) { sql.append(" AND v.city=?"); args.add(city.trim()); }
        if (date != null) {
            sql.append(" AND ss.starts_at>=? AND ss.starts_at<?");
            args.add(date.atStartOfDay());
            args.add(date.plusDays(1).atStartOfDay());
        }
        sql.append(") ORDER BY sh.id");
        return jdbc.query(sql.toString(), (rs, n) -> new Show(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getInt("duration_minutes")), args.toArray());
    }

    public boolean showExists(long showId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM show_catalog WHERE id=?", Boolean.class, showId));
    }

    public List<ShowSession> listSessions(long showId) {
        return jdbc.query("SELECT id,show_id,hall_id,starts_at,sales_start_at,sales_end_at,status,layout_version FROM show_session WHERE show_id=? ORDER BY starts_at,id", SESSION_MAPPER, showId);
    }

    public Optional<ShowSession> findSession(long sessionId) {
        return jdbc.query("SELECT id,show_id,hall_id,starts_at,sales_start_at,sales_end_at,status,layout_version FROM show_session WHERE id=?", SESSION_MAPPER, sessionId).stream().findFirst();
    }

    public List<SessionSeat> listSessionSeats(long sessionId) {
        return jdbc.query("SELECT session_id,seat_id,row_no,seat_no,position,block_id,price_zone,price,status,owner_order_id,hold_expires_at,x,y FROM session_seat WHERE session_id=? ORDER BY row_no,block_id,position,seat_id",
                (rs, n) -> new SessionSeat(rs.getLong("session_id"), rs.getLong("seat_id"), rs.getString("row_no"), rs.getString("seat_no"), rs.getInt("position"), rs.getString("block_id"), rs.getString("price_zone"), rs.getBigDecimal("price"), rs.getString("status"), rs.getObject("owner_order_id", Long.class), instant(rs, "hold_expires_at"), rs.getBigDecimal("x"), rs.getBigDecimal("y")), sessionId);
    }

    private static final RowMapper<ShowSession> SESSION_MAPPER = (rs, n) -> new ShowSession(rs.getLong("id"), rs.getLong("show_id"), rs.getLong("hall_id"), instant(rs,"starts_at"), instant(rs,"sales_start_at"), instant(rs,"sales_end_at"), rs.getString("status"), rs.getInt("layout_version"));
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        // DATETIME values are UTC by schema contract, independent of the JVM default zone.
        java.time.LocalDateTime value = rs.getObject(column, java.time.LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
