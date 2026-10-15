package io.github.yibenwulong.seatflow.messaging;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
@Configuration
@EnableScheduling
public class RabbitMessagingConfig {
    @Bean RabbitRoutes rabbitRoutes(@Value("${seatflow.messaging.namespace:seatflow}") String prefix) {return RabbitRoutes.named(prefix);}
    @Bean RabbitTemplateCustomizer mandatoryPublishing() {return template->template.setMandatory(true);}
    @Bean Declarables orderTopology(RabbitRoutes routes) {return topology(routes);}
    public static Declarables topology(RabbitRoutes r) {
        var exchange=new DirectExchange(r.exchange(),true,false);
        var events=QueueBuilder.durable(r.events()).deadLetterExchange(r.exchange()).deadLetterRoutingKey("dead").build();
        var expiry=QueueBuilder.durable(r.expiry()).deadLetterExchange(r.exchange()).deadLetterRoutingKey("dead").build();
        var delay=QueueBuilder.durable(r.delay()).ttl(60000).deadLetterExchange(r.exchange()).deadLetterRoutingKey("expiry").build();
        var dead=QueueBuilder.durable(r.dead()).maxLength(10000).ttl(604800000).build();
        return new Declarables(exchange,events,expiry,delay,dead,
                BindingBuilder.bind(events).to(exchange).with("events"),BindingBuilder.bind(expiry).to(exchange).with("expiry"),
                BindingBuilder.bind(delay).to(exchange).with("delay"),BindingBuilder.bind(dead).to(exchange).with("dead"));
    }
    @Bean @ConditionalOnProperty(name="seatflow.messaging.publisher-enabled",havingValue="true",matchIfMissing=true)
    PublisherSchedule publisherSchedule(OutboxPublisher publisher) {return new PublisherSchedule(publisher);}
    @Bean @ConditionalOnProperty(name="seatflow.messaging.expiry-enabled",havingValue="true",matchIfMissing=true)
    ExpirySchedule expirySchedule(OrderExpiryJob job) {return new ExpirySchedule(job);}
    static class PublisherSchedule {
        private final OutboxPublisher publisher; PublisherSchedule(OutboxPublisher publisher){this.publisher=publisher;}
        @Scheduled(fixedDelay=1000,initialDelay=1000) public void run(){publisher.publishBatch();}
    }
    static class ExpirySchedule {
        private final OrderExpiryJob job; ExpirySchedule(OrderExpiryJob job){this.job=job;}
        @Scheduled(fixedDelay=5000,initialDelay=5000) public void run(){job.scheduledRun();}
    }
}
