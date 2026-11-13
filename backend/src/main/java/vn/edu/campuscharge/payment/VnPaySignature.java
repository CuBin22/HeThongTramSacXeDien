package vn.edu.campuscharge.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VnPaySignature {
    private VnPaySignature() {}

    public static String canonical(Map<String, ? extends java.util.List<String>> fields) {
        return fields.entrySet().stream()
            .filter(e -> !e.getKey().equals("vnp_SecureHash") && !e.getKey().equals("vnp_SecureHashType"))
            .sorted(Map.Entry.comparingByKey())
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue().getFirst()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    public static String hmacSha512Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA512 is unavailable", e);
        }
    }

    public static boolean isValid(Map<String, ? extends java.util.List<String>> fields, String secret) {
        var hashes = fields.get("vnp_SecureHash");
        if (hashes == null || hashes.size() != 1 || hashes.getFirst() == null || !hashes.getFirst().matches("(?i)[0-9a-f]{128}"))
            return false;
        if (fields.entrySet().stream().anyMatch(e -> e.getValue() == null || e.getValue().size() != 1)) return false;
        byte[] provided;
        try { provided = HexFormat.of().parseHex(hashes.getFirst()); }
        catch (IllegalArgumentException e) { return false; }
        byte[] expected = HexFormat.of().parseHex(hmacSha512Hex(canonical(fields), secret));
        return MessageDigest.isEqual(expected, provided);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
