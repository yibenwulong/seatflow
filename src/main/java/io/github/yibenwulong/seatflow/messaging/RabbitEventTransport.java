package io.github.yibenwulong.seatflow.messaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Component
public class RabbitEventTransport implements EventTransport {
    private final RabbitTemplate template;private final ObjectMapper mapper;private final Clock clock;private final RabbitRoutes routes;
    public RabbitEventTransport(RabbitTemplate template,ObjectMapper mapper,Clock clock,RabbitRoutes routes) {
        this.template=template;this.mapper=mapper;this.clock=clock;this.routes=routes;
    }
    public void publish(OutboxClaim claim) throws Exception {
        var payload=mapper.readTree(claim.payload());
        var event=new OrderEvent(claim.eventId(),claim.eventType(),payload.path("orderId").asText(),payload.path("sessionId").asText(),payload.path("expireAt").asText());
        send(event,"events",null);
        if("ORDER_CREATED".equals(event.eventType())) sendHint(event);
    }
    public void sendHint(OrderEvent event) throws Exception {
        send(event,"delay",Long.toString(hintDelayMillis(clock.instant(),Instant.parse(event.expireAt()))));
    }
    private void send(OrderEvent event,String route,String expiration) throws Exception {
        var properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(event.eventId());
        if(expiration!=null) properties.setExpiration(expiration);
        var correlation=new CorrelationData(UUID.randomUUID().toString()); // attempt identity, not dedup identity
        template.send(routes.exchange(),route,new Message(mapper.writeValueAsBytes(event),properties),correlation);
        var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        // Spring guarantees returned is populated before the ACK future completes.
        if(correlation.getReturned()!=null) throw new IllegalStateException("Broker returned unroutable message: "+correlation.getReturned().getReplyText());
        if(!confirm.isAck()) throw new IllegalStateException("Broker NACK: "+confirm.getReason());
    }
    static long hintDelayMillis(Instant now,Instant deadline) {
        return Math.max(1,Math.min(60000,Duration.between(now,deadline).toMillis()));
    }
}
