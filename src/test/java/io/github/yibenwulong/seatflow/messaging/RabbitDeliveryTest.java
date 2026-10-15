package io.github.yibenwulong.seatflow.messaging;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class RabbitDeliveryTest {
    @Test void ackWithMandatoryReturnIsFailureAndCannotBeSent() {
        var template=mock(RabbitTemplate.class);
        doAnswer(invocation->{
            CorrelationData data=invocation.getArgument(3);
            data.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","exchange","missing"));
            data.getFuture().complete(new CorrelationData.Confirm(true,null)); return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var transport=new RabbitEventTransport(template,JsonMapper.builder().build(),Clock.systemUTC(),RabbitRoutes.named("test"));
        assertThatThrownBy(()->transport.publish(new OutboxClaim("one","ORDER_CANCELLED",1,"{\"orderId\":\"1\",\"sessionId\":\"101\",\"expireAt\":\"2026-10-15T00:00:00Z\"}",1,"token"))).hasMessageContaining("returned");
    }
    @Test void nackIsFailureEvenWithoutReturn() {
        var template=mock(RabbitTemplate.class);
        doAnswer(invocation->{
            CorrelationData data=invocation.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(false,"broker fault")); return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var transport=new RabbitEventTransport(template,JsonMapper.builder().build(),Clock.systemUTC(),RabbitRoutes.named("test"));
        assertThatThrownBy(()->transport.publish(new OutboxClaim("nack","ORDER_CANCELLED",1,"{\"orderId\":\"1\",\"sessionId\":\"101\",\"expireAt\":\"2026-10-15T00:00:00Z\"}",1,"token"))).hasMessageContaining("NACK");
    }
    @Test void poisonMessageIsRejectedWithoutImmediateRequeue() {
        var listener=new RabbitOrderListener(JsonMapper.builder().build(),mock(OrderEventConsumer.class),mock(RabbitEventTransport.class),Clock.systemUTC());
        assertThatThrownBy(()->listener.event(new Message("invalid JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    }
    @Test void ttlUsesRemainingAbsoluteDeadlineAndCapsEachHop() {
        var now=Instant.parse("2026-10-15T00:00:00Z");
        assertThat(RabbitEventTransport.hintDelayMillis(now,now.plusSeconds(900))).isEqualTo(60000);
        assertThat(RabbitEventTransport.hintDelayMillis(now.plusSeconds(895),now.plusSeconds(900))).isEqualTo(5000);
        assertThat(RabbitEventTransport.hintDelayMillis(now.plusSeconds(901),now.plusSeconds(900))).isEqualTo(1);
    }
}
