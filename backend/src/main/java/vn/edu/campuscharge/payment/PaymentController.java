package vn.edu.campuscharge.payment;

import java.security.Principal;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.payment.PaymentRepository.PaymentView;

@RestController
@org.springframework.context.annotation.Profile("!simulator")
@RequestMapping("/api")
public class PaymentController {
    private final PaymentService service;
    public PaymentController(PaymentService service) { this.service=service; }

    @PostMapping("/sessions/{id}/payments") @ResponseStatus(HttpStatus.CREATED)
    PaymentService.PaymentStart start(Principal principal,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
                                      HttpServletRequest request) {
        return service.start(ApiAccess.user(principal),id,ApiAccess.key(key),request.getRemoteAddr());
    }

    @GetMapping("/payments/{id}")
    PaymentView get(Principal principal,@PathVariable UUID id) { return service.get(ApiAccess.user(principal),id); }

    @GetMapping("/payments")
    List<PaymentView> list(Principal principal) { return service.list(ApiAccess.user(principal)); }

    @GetMapping("/payments/vnpay/return")
    Map<String,Object> returnStatus(Principal principal,HttpServletRequest request) {
        var fields=new LinkedHashMap<String,List<String>>();
        request.getParameterMap().forEach((key,values)-> {
            if(key.startsWith("vnp_")) fields.put(key,Arrays.asList(values));
        });
        var payment=service.returnStatus(ApiAccess.user(principal),fields);
        return Map.of("paymentId",payment.paymentId(),"sessionId",payment.sessionId(),"status",payment.status());
    }

    @GetMapping("/payments/vnpay/ipn")
    Map<String,String> ipn(HttpServletRequest request) {
        var fields=new LinkedHashMap<String,List<String>>();
        request.getParameterMap().forEach((key,values)-> {
            if(key.startsWith("vnp_")) fields.put(key,Arrays.asList(values));
        });
        var result=service.ipn(fields);
        return Map.of("RspCode",result.responseCode(),"Message",result.message());
    }
}
