package io.github.yibenwulong.seatflow.messaging;
@FunctionalInterface
public interface EventTransport { void publish(OutboxClaim event) throws Exception; }
