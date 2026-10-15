package io.github.yibenwulong.seatflow.payment;
import java.util.Map;
public record NotificationInput(Map<String,String> fields) {
    public NotificationInput { fields = Map.copyOf(fields); }
}
