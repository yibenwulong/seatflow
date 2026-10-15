package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.common.ApiException;
import io.github.yibenwulong.seatflow.identity.CurrentUser;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentService payments;
    private final RefundService refunds;
    public PaymentController(PaymentService payments,RefundService refunds) { this.payments=payments; this.refunds=refunds; }
    @PostMapping("/orders/{id}/payments")
    public PaymentView create(@PathVariable long id) { return payments.create(CurrentUser.requireUser().id(),id); }
    @PostMapping("/payments/{provider}/notify")
    public String notify(@PathVariable String provider,@RequestParam MultiValueMap<String,String> fields) {
        var single=new HashMap<String,String>();
        fields.forEach((key,values)->{
            if(values.size()!=1) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NOTIFICATION","Duplicate notification field");
            single.put(key,values.get(0));
        });
        payments.notify(provider,new NotificationInput(single));
        return "success";
    }
    @PostMapping("/orders/{id}/refunds")
    public RefundView refund(@PathVariable long id) { return refunds.request(CurrentUser.requireUser().id(),id); }
    @GetMapping("/orders/{id}/refunds")
    public List<RefundView> refunds(@PathVariable long id) { return refunds.list(CurrentUser.requireUser().id(),id); }
}
