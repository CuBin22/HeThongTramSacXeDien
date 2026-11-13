package vn.edu.campuscharge.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("!simulator")
public class VnPayProperties {
    private final String provider;
    private final String tmnCode;
    private final String hashSecret;
    private final String paymentUrl;
    private final String returnUrl;

    public VnPayProperties(@Value("${payment.provider:disabled}") String provider,
                           @Value("${vnpay.tmn-code:}") String tmnCode,
                           @Value("${vnpay.hash-secret:}") String hashSecret,
                           @Value("${vnpay.url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}") String paymentUrl,
                           @Value("${vnpay.return-url:}") String returnUrl) {
        this.provider = provider.trim().toLowerCase(java.util.Locale.ROOT);
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.paymentUrl = paymentUrl;
        this.returnUrl = returnUrl;
        if (!this.provider.equals("disabled") && !this.provider.equals("vnpay"))
            throw new IllegalStateException("PAYMENT_PROVIDER must be vnpay or disabled");
        if (this.provider.equals("vnpay") && (!StringUtils.hasText(tmnCode) || !StringUtils.hasText(hashSecret)
                || !validUrl(paymentUrl) || !validUrl(returnUrl)))
            throw new IllegalStateException("VNPay requires VNPAY_TMN_CODE, VNPAY_HASH_SECRET, VNPAY_URL and VNPAY_RETURN_URL");
    }

    private static boolean validUrl(String value) {
        try { return StringUtils.hasText(value) && java.net.URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException e) { return false; }
    }

    public String provider() { return provider; }
    public String tmnCode() { return tmnCode; }
    public String hashSecret() { return hashSecret; }
    public String paymentUrl() { return paymentUrl; }
    public String returnUrl() { return returnUrl; }
}
