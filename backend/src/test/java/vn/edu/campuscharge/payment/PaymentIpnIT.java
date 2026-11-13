package vn.edu.campuscharge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.campuscharge.api.ApiTestSupport;

@SpringBootTest(properties = {
    "payment.provider=vnpay", "vnpay.tmn-code=TESTTMN", "vnpay.hash-secret=test-secret",
    "vnpay.url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
    "vnpay.return-url=http://localhost:8080/api/payments/vnpay/return"
})
@AutoConfigureMockMvc
class PaymentIpnIT extends ApiTestSupport {
    @Test void createsOnePendingPaymentAndReturnsOwnerOnlyView() throws Exception {
        UUID session = completedSession(USER, "2500.00");
        var response = mvc.perform(post("/api/sessions/{id}/payments", session).with(authentication(owner())).with(csrf())
                .header("Idempotency-Key", "pay-once"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var body = json.readTree(response);
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("redirectUrl").asText()).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(body.get("redirectUrl").asText()).contains("vnp_Amount=250000");
        var redirectFields=decode(URI.create(body.get("redirectUrl").asText()).getRawQuery());
        assertThat(VnPaySignature.isValid(redirectFields,"test-secret")).isTrue();
        var repeated = mvc.perform(post("/api/sessions/{id}/payments", session).with(authentication(owner())).with(csrf())
                .header("Idempotency-Key", "pay-once"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(repeated).get("paymentId").asText()).isEqualTo(body.get("paymentId").asText());
        assertThat(json.readTree(repeated).get("redirectUrl").asText()).isEqualTo(body.get("redirectUrl").asText());
        mvc.perform(get("/api/payments/{id}", body.get("paymentId").asText()).with(authentication(other())))
            .andExpect(status().isForbidden());
    }

    @Test void verifiedFailureAndCancellationAreRecordedWithoutBecomingSuccessful() throws Exception {
        UUID failedSession=completedSession(USER,"410.00"), cancelledSession=completedSession(USER,"420.00");
        createPayment(failedSession,"failed-key"); createPayment(cancelledSession,"cancel-key");
        mvc.perform(callback(reference(failedSession),"41000","05","05","TXN-FAIL")).andExpect(status().isOk());
        mvc.perform(callback(reference(cancelledSession),"42000","24","02","TXN-CANCEL")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE session_id=?",String.class,failedSession)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE session_id=?",String.class,cancelledSession)).isEqualTo("CANCELLED");
    }

    @Test void rejectsAProviderTransactionIdAlreadyUsedByAnotherPayment() throws Exception {
        UUID first=completedSession(USER,"430.00"), second=completedSession(USER,"440.00");
        createPayment(first,"txn-first"); createPayment(second,"txn-second");
        mvc.perform(callback(reference(first),"43000","00","00","SHARED-TXN")).andExpect(status().isOk());
        mvc.perform(callback(reference(second),"44000","00","00","SHARED-TXN")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE session_id=?",String.class,first)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE session_id=?",String.class,second)).isEqualTo("PENDING");
    }

    @Test void verifiedIpnCanSucceedOnceAndLateFailureCannotReverseIt() throws Exception {
        UUID session = completedSession(USER, "3750.00");
        String paymentId = createPayment(session, "success-key");
        String ref = reference(session);
        var success = callback(ref, "375000", "00", "00", "TXN-1");
        mvc.perform(success).andExpect(status().isOk());
        mvc.perform(success).andExpect(status().isOk());
        mvc.perform(callback(ref, "375000", "24", "02", "TXN-1")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE transaction_reference=?", String.class, ref)).isEqualTo("SUCCEEDED");
    }

    @Test void rejectsBadSignatureWrongReferenceWrongAmountAndMerchantCode() throws Exception {
        UUID session = completedSession(USER, "1250.00");
        createPayment(session, "verify-key");
        String ref = reference(session);
        mvc.perform(callback(ref, "125000", "00", "00", "TXN-A").param("vnp_SecureHash", "bad"))
            .andExpect(status().isOk());
        mvc.perform(callback("unknown-ref", "125000", "00", "00", "TXN-B")).andExpect(status().isOk());
        mvc.perform(callback(ref, "125100", "00", "00", "TXN-C")).andExpect(status().isOk());
        mvc.perform(callback(ref, "125000", "00", "00", "TXN-D").param("vnp_TmnCode", "OTHER"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE transaction_reference=?", String.class, ref)).isEqualTo("PENDING");
    }

    @Test void signedBrowserReturnFinalizesPayment() throws Exception {
        UUID session = completedSession(USER, "990.00");
        createPayment(session, "return-key");
        mvc.perform(callback("/api/payments/vnpay/return", reference(session), "99000", "00", "00", "TXN-RETURN")
                .with(authentication(owner())))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE transaction_reference=?", String.class, reference(session))).isEqualTo("SUCCEEDED");
    }

    @Test void refusesAnotherKeyForPendingPaymentAndAmountDoesNotComeFromRequest() throws Exception {
        UUID session = completedSession(USER, "810.00");
        createPayment(session, "key-1");
        mvc.perform(post("/api/sessions/{id}/payments", session).with(authentication(owner())).with(csrf())
                .header("Idempotency-Key", "key-2").contentType("application/json").content("{\"amount\":1}"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT amount_vnd FROM payment_transactions WHERE session_id=?", java.math.BigDecimal.class, session))
            .isEqualByComparingTo("810.00");
    }

    private String createPayment(UUID session, String key) throws Exception {
        var response = mvc.perform(post("/api/sessions/{id}/payments", session).with(authentication(owner())).with(csrf()).header("Idempotency-Key", key))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("paymentId").asText();
    }

    private String reference(UUID session) {
        return jdbc.queryForObject("SELECT transaction_reference FROM payment_transactions WHERE session_id=?", String.class, session);
    }

    private Map<String,List<String>> decode(String query) {
        var fields=new LinkedHashMap<String,List<String>>();
        for(String part:query.split("&")) {
            String[] pair=part.split("=",2);
            fields.put(java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8),
                List.of(java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8)));
        }
        return fields;
    }

    private MockHttpServletRequestBuilder callback(String ref, String amount, String code, String txStatus, String transaction) {
        return callback("/api/payments/vnpay/ipn",ref,amount,code,txStatus,transaction);
    }

    private MockHttpServletRequestBuilder callback(String path, String ref, String amount, String code, String txStatus, String transaction) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("vnp_TmnCode", "TESTTMN"); fields.put("vnp_Amount", amount); fields.put("vnp_BankCode", "NCB");
        fields.put("vnp_CardType", "ATM"); fields.put("vnp_OrderInfo", "Charging session"); fields.put("vnp_PayDate", "20261026200000");
        fields.put("vnp_ResponseCode", code); fields.put("vnp_TxnRef", ref); fields.put("vnp_TransactionNo", transaction);
        fields.put("vnp_TransactionStatus", txStatus);
        var signed = new LinkedHashMap<String, List<String>>(); fields.forEach((k,v)->signed.put(k,List.of(v)));
        signed.put("vnp_SecureHash", List.of(VnPaySignature.hmacSha512Hex(VnPaySignature.canonical(signed), "test-secret")));
        var request = get(path); fields.forEach(request::param);
        request.param("vnp_SecureHash", signed.get("vnp_SecureHash").getFirst());
        return request;
    }

    private UUID completedSession(UUID user, String amount) {
        UUID booking=UUID.randomUUID(), session=UUID.randomUUID(), stop=UUID.randomUUID();
        jdbc.update("INSERT INTO bookings(id,user_id,station_id,port_id,start_at,end_at,status,idempotency_key) VALUES (?,?,?,?,now()-interval '1 day',now()+interval '1 day','COMPLETED',?)",
            booking,user,STATION,PORT,"pay-booking-"+booking);
        jdbc.update("INSERT INTO charging_sessions(id,booking_id,port_id,tariff_id,price_per_kwh,initial_battery_percent,battery_percent,status,idempotency_key,started_at) VALUES (?,?,?,?,3000,30,100,'START_REQUESTED',?,now()-interval '1 hour')",
            session,booking,PORT,jdbc.queryForObject("SELECT id FROM tariffs LIMIT 1",UUID.class),"pay-session-"+session);
        Instant ended=Instant.now();
        var endedAt = java.sql.Timestamp.from(ended);
        jdbc.update("INSERT INTO device_commands(id,session_id,port_id,type,status,idempotency_key,acknowledged_at) VALUES (?,?,?,'STOP','ACKNOWLEDGED',?,?)",
            stop,session,PORT,"pay-stop-"+session,endedAt);
        jdbc.update("UPDATE charging_sessions SET status='COMPLETED',energy_kwh=1,total_fee=?,ended_at=?,stop_command_id=? WHERE id=?",new java.math.BigDecimal(amount),endedAt,stop,session);
        return session;
    }
}
