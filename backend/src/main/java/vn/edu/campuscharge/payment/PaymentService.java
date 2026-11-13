package vn.edu.campuscharge.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.payment.PaymentRepository.*;

@Service
@org.springframework.context.annotation.Profile("!simulator")
public class PaymentService {
    public record PaymentStart(UUID paymentId,String status,String redirectUrl) {}
    public record CallbackResult(String responseCode,String message) {}
    private final PaymentRepository payments;
    private final VnPayClient client;
    private final VnPayProperties config;

    public PaymentService(PaymentRepository payments,VnPayClient client,VnPayProperties config) { this.payments=payments;this.client=client;this.config=config; }

    @Transactional
    public PaymentStart start(UUID userId,UUID sessionId,String key,String ip) {
        requireEnabled();
        Charge charge=payments.lockCharge(sessionId); ApiAccess.owner(charge.userId(),userId);
        if(!"COMPLETED".equals(charge.status()) || charge.amount()==null) throw ApiAccess.conflict("Charging session is not complete");
        BigDecimal amount;
        try { amount=charge.amount().setScale(0,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException invalid) { throw ApiAccess.conflict("VNPay supports whole VND amounts only"); }
        if(amount.signum()<=0) throw ApiAccess.conflict("Session has no payable fee");
        var existing=payments.bySessionKey(sessionId,key);
        if(existing.isPresent()) {
            PaymentView row=existing.get();
            if(row.amountVnd().compareTo(amount)!=0) throw ApiAccess.conflict("Idempotency key has a different amount");
            return startView(row);
        }
        if(payments.activeForSession(sessionId).isPresent()) throw ApiAccess.conflict("Session already has a pending or successful payment");
        String ref=client.newReference();
        String redirect=client.redirectUrl(ref,amount,ip);
        return startView(payments.insert(sessionId,ref,amount,key,redirect));
    }

    public PaymentView get(UUID userId,UUID paymentId) { return payments.getOwned(paymentId,userId); }
    public java.util.List<PaymentView> list(UUID userId) { return payments.listForUser(userId); }

    @Transactional
    public PaymentView returnStatus(UUID userId,java.util.Map<String,? extends java.util.List<String>> fields) {
        String ref=single(fields,"vnp_TxnRef");
        if(ref==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid VNPay return");
        payments.byReferenceForUser(ref,userId);
        if(fields.containsKey("vnp_SecureHash")) {
            CallbackResult result=recordVnPayResult(fields);
            if(!"00".equals(result.responseCode())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not verify VNPay return");
        }
        return payments.byReferenceForUser(ref,userId);
    }

    @Transactional
    public CallbackResult ipn(java.util.Map<String,? extends java.util.List<String>> fields) {
        requireEnabled();
        return recordVnPayResult(fields);
    }

    private CallbackResult recordVnPayResult(java.util.Map<String,? extends java.util.List<String>> fields) {
        if(!VnPaySignature.isValid(fields,config.hashSecret())) return new CallbackResult("97","Invalid signature");
        String tmn=single(fields,"vnp_TmnCode"),ref=single(fields,"vnp_TxnRef"),amountText=single(fields,"vnp_Amount"),txn=single(fields,"vnp_TransactionNo");
        String response=single(fields,"vnp_ResponseCode"),transactionStatus=single(fields,"vnp_TransactionStatus");
        if(tmn==null || ref==null || amountText==null || txn==null || response==null || transactionStatus==null)
            return new CallbackResult("99","Invalid parameters");
        if(!config.tmnCode().equals(tmn)) return new CallbackResult("04","Invalid merchant");
        long amount;
        try { amount=Long.parseLong(amountText); if(amount<=0) return new CallbackResult("04","Invalid amount"); }
        catch(NumberFormatException invalid) { return new CallbackResult("04","Invalid amount"); }
        CallbackPayment payment=payments.lockByReference(ref);
        if(payment==null) return new CallbackResult("01","Order not found");
        if(payments.providerTransactionUsedByAnother(payment.paymentId(),txn)) return new CallbackResult("04","Provider transaction already used");
        try {
            if(payment.amountVnd().movePointRight(2).longValueExact()!=amount) return new CallbackResult("04","Invalid amount");
        } catch(ArithmeticException invalid) { return new CallbackResult("04","Invalid amount"); }
        boolean success="00".equals(response) && "00".equals(transactionStatus);
        String target=success?"SUCCEEDED":("24".equals(response)?"CANCELLED":"FAILED");
        if("SUCCEEDED".equals(payment.status())) {
            if(success && txn.equals(payment.providerTransactionId())) return new CallbackResult("00","Confirm Success");
            if(success) return new CallbackResult("04","Transaction already confirmed differently");
            return new CallbackResult("00","Confirm Success");
        }
        if(!"PENDING".equals(payment.status())) return new CallbackResult("00","Confirm Success");
        int changed=success?payments.succeed(payment.paymentId(),txn):payments.finish(payment.paymentId(),txn,target);
        return changed==1?new CallbackResult("00","Confirm Success"):new CallbackResult("04","Payment state conflict");
    }

    private String single(java.util.Map<String,? extends java.util.List<String>> fields,String name) {
        var values=fields.get(name); return values==null || values.size()!=1?null:values.getFirst();
    }
    private void requireEnabled() {
        if(!"vnpay".equals(config.provider())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"VNPay payment is disabled");
    }
    private static PaymentStart startView(PaymentView payment) { return new PaymentStart(payment.paymentId(),payment.status(),payment.redirectUrl()); }
}
