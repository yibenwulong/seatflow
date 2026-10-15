package io.github.yibenwulong.seatflow.messaging;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.core.*;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
/** Requires a real configured RabbitMQ; absent broker/config fails explicitly. */
class OutboxDeliveryIT {
    @Test void realConfirmReturnAndBrokerTtlUseWallClock() throws Exception {
        String host=required("SPRING_RABBITMQ_HOST");
        var cf=new CachingConnectionFactory(host,Integer.parseInt(required("SPRING_RABBITMQ_PORT")));
        cf.setUsername(required("SPRING_RABBITMQ_USERNAME"));cf.setPassword(required("SPRING_RABBITMQ_PASSWORD"));
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);cf.setPublisherReturns(true);
        var admin=new RabbitAdmin(cf);var routes=RabbitRoutes.named("seatflow.it."+UUID.randomUUID());
        try {
            RabbitMessagingConfig.topology(routes).getDeclarables().forEach(d->{
                if(d instanceof Exchange e) admin.declareExchange(e);
                else if(d instanceof Queue q) admin.declareQueue(q);
                else if(d instanceof Binding b) admin.declareBinding(b);
            });
            var template=new RabbitTemplate(cf);template.setMandatory(true);template.setReceiveTimeout(10000);
            var transport=new RabbitEventTransport(template,JsonMapper.builder().build(),Clock.systemUTC(),routes);
            Instant deadline=Instant.now().plusMillis(3000);
            String payload="{\"orderId\":\"1\",\"sessionId\":\"101\",\"expireAt\":\""+deadline+"\"}";
            transport.publish(new OutboxClaim("real-event","ORDER_CREATED",1,payload,1,"claim"));
            assertThat(template.receive(routes.events())).isNotNull();
            assertThat(template.receive(routes.expiry(),100)).isNull();
            var hint=template.receive(routes.expiry(),10000);
            assertThat(hint).isNotNull();
            assertThat(Instant.now()).isAfterOrEqualTo(deadline.minusMillis(100));
            admin.removeBinding(new Binding(routes.events(),Binding.DestinationType.QUEUE,routes.exchange(),"events",null));
            assertThatThrownBy(()->transport.publish(new OutboxClaim("unrouted","ORDER_CANCELLED",1,payload,1,"claim"))).hasMessageContaining("returned");
        } finally {
            admin.deleteQueue(routes.events());admin.deleteQueue(routes.delay());admin.deleteQueue(routes.expiry());admin.deleteQueue(routes.dead());admin.deleteExchange(routes.exchange());cf.destroy();
        }
    }
    @Test void realBrokerAckMarksSentAndMandatoryReturnRetainsPendingOutbox() throws Exception {
        var cf=new CachingConnectionFactory(required("SPRING_RABBITMQ_HOST"),Integer.parseInt(required("SPRING_RABBITMQ_PORT")));
        cf.setUsername(required("SPRING_RABBITMQ_USERNAME"));cf.setPassword(required("SPRING_RABBITMQ_PASSWORD"));
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);cf.setPublisherReturns(true);
        var admin=new RabbitAdmin(cf);var routes=RabbitRoutes.named("seatflow.it."+UUID.randomUUID());
        try(var f=new MessagingFixture()) {
            RabbitMessagingConfig.topology(routes).getDeclarables().forEach(d->{
                if(d instanceof Exchange e) admin.declareExchange(e);
                else if(d instanceof Queue q) admin.declareQueue(q);
                else if(d instanceof Binding b) admin.declareBinding(b);
            });
            var template=new RabbitTemplate(cf);template.setMandatory(true);
            var transport=new RabbitEventTransport(template,JsonMapper.builder().build(),Clock.systemUTC(),routes);
            String payload="{\"orderId\":\"1\",\"sessionId\":\"101\",\"expireAt\":\"2026-10-15T00:00:00Z\"}";
            f.tx.executeWithoutResult(st->f.outbox.append("ORDER_CANCELLED",1,payload));
            new OutboxPublisher(f.outbox,transport,f.clock).publishBatch();
            assertThat(f.jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=1",String.class)).isEqualTo("SENT");
            assertThat(template.receive(routes.events(),5000)).isNotNull();
            admin.removeBinding(new Binding(routes.events(),Binding.DestinationType.QUEUE,routes.exchange(),"events",null));
            f.tx.executeWithoutResult(st->f.outbox.append("ORDER_CANCELLED",2,payload));
            new OutboxPublisher(f.outbox,transport,f.clock).publishBatch();
            assertThat(f.jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=2",String.class)).isEqualTo("PENDING");
            assertThat(f.jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE aggregate_id=2",String.class)).contains("returned");
        } finally {
            admin.deleteQueue(routes.events());admin.deleteQueue(routes.delay());admin.deleteQueue(routes.expiry());admin.deleteQueue(routes.dead());admin.deleteExchange(routes.exchange());cf.destroy();
        }
    }
    @Test void realDeliveryDeduplicatesDueExpiryAndDeadLettersPoison() throws Exception {
        var cf=new CachingConnectionFactory(required("SPRING_RABBITMQ_HOST"),Integer.parseInt(required("SPRING_RABBITMQ_PORT")));
        cf.setUsername(required("SPRING_RABBITMQ_USERNAME"));cf.setPassword(required("SPRING_RABBITMQ_PASSWORD"));
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);cf.setPublisherReturns(true);
        var admin=new RabbitAdmin(cf);var routes=RabbitRoutes.named("seatflow.it."+UUID.randomUUID());
        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer events=null,expiry=null;
        try(var f=new MessagingFixture()) {
            RabbitMessagingConfig.topology(routes).getDeclarables().forEach(d->{
                if(d instanceof Exchange e) admin.declareExchange(e);
                else if(d instanceof Queue q) admin.declareQueue(q);
                else if(d instanceof Binding b) admin.declareBinding(b);
            });
            var mapper=JsonMapper.builder().build();var template=new RabbitTemplate(cf);template.setMandatory(true);
            var transport=new RabbitEventTransport(template,mapper,f.clock,routes);
            var consumer=new OrderEventConsumer(f.jdbc,f.orders,f.service,org.mockito.Mockito.mock(io.github.yibenwulong.seatflow.seating.SeatMapCache.class),f.transactions,f.clock);
            var listener=new RabbitOrderListener(mapper,consumer,transport,f.clock);
            var order=f.create();f.clock.advance(Duration.ofMinutes(15));
            var claim=f.outbox.claim(f.clock.instant(),Duration.ofSeconds(30),1).get(0);
            events=new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(cf);
            events.setQueueNames(routes.events());events.setDefaultRequeueRejected(false);
            events.setMessageListener((MessageListener)listener::event);events.start();
            expiry=new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(cf);
            expiry.setQueueNames(routes.expiry());expiry.setDefaultRequeueRejected(false);
            expiry.setMessageListener((MessageListener)listener::expiry);expiry.start();
            transport.publish(claim);transport.publish(claim);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
                assertThat(f.orders.findOrder(order.id()).orElseThrow().status()).isEqualTo("CANCELLED");
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isEqualTo(2);
                assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("AVAILABLE");
            });
            template.send(routes.exchange(),"events",new Message("poison".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(template.receive(routes.dead(),10000)).isNotNull();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isEqualTo(2);
            events.stop();expiry.stop();
        } finally {
            if(events!=null) events.stop();if(expiry!=null) expiry.stop();
            admin.deleteQueue(routes.events());admin.deleteQueue(routes.delay());admin.deleteQueue(routes.expiry());admin.deleteQueue(routes.dead());admin.deleteExchange(routes.exchange());cf.destroy();
        }
    }
    static String required(String key) {String v=System.getenv(key);if(v==null||v.isBlank()) throw new IllegalStateException(key+" required: real RabbitMQ test cannot run without broker configuration");return v;}
}
