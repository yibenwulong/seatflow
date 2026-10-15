package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.identity.CurrentUser;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.bind.annotation.*;

@RestController
@Conditional(MockPaymentController.DevelopmentOnly.class)
public class MockPaymentController {
    private final PaymentService payments;
    public MockPaymentController(PaymentService payments) { this.payments=payments; }
    @PostMapping("/api/v1/dev/payments/{id}/complete")
    public Map<String,String> complete(@PathVariable long id) {
        payments.completeMock(CurrentUser.requireUser().id(),id);
        return Map.of("status","PROCESSED");
    }
    static final class DevelopmentOnly implements Condition {
        @Override public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata) {
            String[] profiles=context.getEnvironment().getActiveProfiles();
            return profiles.length>0 && java.util.Arrays.stream(profiles).allMatch(p->p.equals("local") || p.equals("test"));
        }
    }
}
