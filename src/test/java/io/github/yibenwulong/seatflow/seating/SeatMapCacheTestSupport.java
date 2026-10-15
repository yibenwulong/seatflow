package io.github.yibenwulong.seatflow.seating;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
/** Isolated namespaces for cross-package component integration tests. */
public final class SeatMapCacheTestSupport {
    private SeatMapCacheTestSupport() {}
    public static SeatMapCache isolated(StringRedisTemplate redis,ObjectMapper mapper,String prefix) {return new SeatMapCache(redis,mapper,prefix);}
}
