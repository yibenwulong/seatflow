package io.github.yibenwulong.seatflow.messaging;
public record RabbitRoutes(String exchange,String events,String delay,String expiry,String dead) {
    public static RabbitRoutes named(String prefix) {return new RabbitRoutes(prefix+".orders",prefix+".events",prefix+".delay",prefix+".expiry",prefix+".dead");}
}
