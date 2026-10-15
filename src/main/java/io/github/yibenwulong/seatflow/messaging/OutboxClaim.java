package io.github.yibenwulong.seatflow.messaging;
public record OutboxClaim(String eventId,String eventType,long aggregateId,String payload,int attempts,String leaseToken) {}
