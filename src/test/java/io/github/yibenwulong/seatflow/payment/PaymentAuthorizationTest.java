package io.github.yibenwulong.seatflow.payment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PaymentAuthorizationTest {
    @Test void mockCompletionIsAbsentWithoutExplicitLocalOrTestAndAlsoInProduction() {
        var runner=new ApplicationContextRunner().withBean(PaymentService.class,()->mock(PaymentService.class)).withUserConfiguration(MockPaymentController.class);
        runner.run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
        runner.withPropertyValues("spring.profiles.active=production").run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
        runner.withPropertyValues("spring.profiles.active=test,production").run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
        runner.withPropertyValues("spring.profiles.active=local,staging").run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
        runner.withPropertyValues("spring.profiles.active=local").run(c->assertThat(c).hasSingleBean(MockPaymentController.class));
    }
}
