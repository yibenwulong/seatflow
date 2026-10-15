package io.github.yibenwulong.seatflow.seating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class SeatMapCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String prefix;
    @Autowired
    public SeatMapCache(StringRedisTemplate redis,ObjectMapper mapper) { this(redis,mapper,"seatflow:seat-map:"); }
    SeatMapCache(StringRedisTemplate redis,ObjectMapper mapper,String prefix) {
        this.redis=redis; this.mapper=mapper; this.prefix=prefix;
    }
    public Optional<SeatMapView> get(long sessionId) {
        try {
            String json=redis.opsForValue().get(prefix+sessionId);
            if(json==null) return Optional.empty();
            SeatMapView view=mapper.readValue(json,SeatMapView.class);
            return view!=null && view.sessionId()==sessionId ? Optional.of(view) : Optional.empty();
        } catch(DataAccessException | JsonProcessingException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
    public void put(SeatMapView view) {
        try { redis.opsForValue().set(prefix+view.sessionId(),mapper.writeValueAsString(view),Duration.ofSeconds(5)); }
        catch(DataAccessException | JsonProcessingException ex) { /* A query cache never controls inventory. */ }
    }
    public void evict(long sessionId) {
        try { redis.delete(prefix+sessionId); }
        catch(DataAccessException ex) { /* Expiry bounds stale data when Redis is unavailable. */ }
    }
}
