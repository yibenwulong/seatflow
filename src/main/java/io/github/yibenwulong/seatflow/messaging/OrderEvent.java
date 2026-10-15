package io.github.yibenwulong.seatflow.messaging;
/** IDs remain strings on the wire. expireAt is an absolute ISO UTC instant. */
public record OrderEvent(String eventId,String eventType,String orderId,String sessionId,String expireAt) {
    public OrderEvent {
        if(eventId==null || eventId.isBlank() || eventId.length()>64) throw new IllegalArgumentException("Invalid event ID");
        if(eventType==null || eventType.isBlank()) throw new IllegalArgumentException("Invalid event type");
        if(Long.parseLong(orderId)<=0 || Long.parseLong(sessionId)<=0) throw new IllegalArgumentException("Invalid business ID");
        java.time.Instant.parse(expireAt);
    }
}
