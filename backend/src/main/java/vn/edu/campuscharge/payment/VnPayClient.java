package vn.edu.campuscharge.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("!simulator")
public class VnPayClient {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final VnPayProperties config;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public VnPayClient(VnPayProperties config) { this(config, Clock.systemUTC()); }
    VnPayClient(VnPayProperties config, Clock clock) { this.config=config; this.clock=clock; }

    public String newReference() { return UUID.randomUUID().toString().replace("-", ""); }

    public String redirectUrl(String reference, BigDecimal amountVnd, String clientIp) {
        long amount = amountVnd.setScale(0, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        var now = LocalDateTime.ofInstant(clock.instant(), VIETNAM);
        var fields = new TreeMap<String,String>();
        fields.put("vnp_Version", "2.1.0"); fields.put("vnp_Command", "pay"); fields.put("vnp_TmnCode", config.tmnCode());
        fields.put("vnp_Amount", Long.toString(amount)); fields.put("vnp_CurrCode", "VND"); fields.put("vnp_TxnRef", reference);
        fields.put("vnp_OrderInfo", "Hospital EV charging session"); fields.put("vnp_OrderType", "other");
        fields.put("vnp_Locale", "vn"); fields.put("vnp_ReturnUrl", config.returnUrl()); fields.put("vnp_IpAddr", clientIp);
        fields.put("vnp_CreateDate", DATE.format(now)); fields.put("vnp_ExpireDate", DATE.format(now.plusMinutes(15)));
        var values = new LinkedHashMap<String,List<String>>(); fields.forEach((k,v)->values.put(k,List.of(v)));
        String canonical = VnPaySignature.canonical(values);
        String hash = VnPaySignature.hmacSha512Hex(canonical, config.hashSecret());
        return config.paymentUrl() + "?" + canonical + "&vnp_SecureHash=" + URLEncoder.encode(hash, StandardCharsets.UTF_8);
    }
}
