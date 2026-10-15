package io.github.yibenwulong.seatflow.messaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.time.*;
@Component
public class RabbitOrderListener {
    private final ObjectMapper mapper;private final OrderEventConsumer consumer;private final RabbitEventTransport transport;private final Clock clock;
    public RabbitOrderListener(ObjectMapper mapper,OrderEventConsumer consumer,RabbitEventTransport transport,Clock clock) {
        this.mapper=mapper;this.consumer=consumer;this.transport=transport;this.clock=clock;
    }
    @RabbitListener(queues="#{rabbitRoutes.events()}",autoStartup="${seatflow.messaging.listener-enabled:true}")
    public void event(Message message) {
        try {consumer.handle(mapper.readValue(message.getBody(),OrderEvent.class));}
        catch(Exception failure) {throw new AmqpRejectAndDontRequeueException("Order event failed; retained in dead queue",failure);}
    }
    @RabbitListener(queues="#{rabbitRoutes.expiry()}",autoStartup="${seatflow.messaging.listener-enabled:true}")
    public void expiry(Message message) {
        try {
            var event=mapper.readValue(message.getBody(),OrderEvent.class);
            if(clock.instant().isBefore(Instant.parse(event.expireAt()))) transport.sendHint(event);
            else consumer.handleExpiry(event);
        } catch(Exception failure) {throw new AmqpRejectAndDontRequeueException("Expiry hint failed; DB scan remains active",failure);}
    }
}
