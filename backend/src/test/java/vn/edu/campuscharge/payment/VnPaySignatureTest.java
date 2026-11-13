package vn.edu.campuscharge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VnPaySignatureTest {
    @Test void canonicalizesSortedKeysWithFormUrlEncodingExactlyOnce() {
        var fields = new LinkedHashMap<String, List<String>>();
        fields.put("vnp_TxnRef", List.of("A B"));
        fields.put("vnp_Amount", List.of("125000"));
        fields.put("vnp_SecureHashType", List.of("HmacSHA512"));
        fields.put("vnp_SecureHash", List.of("ignored"));

        assertThat(VnPaySignature.canonical(fields))
            .isEqualTo("vnp_Amount=125000&vnp_TxnRef=A+B");
    }

    @Test void signsWithUtf8HmacSha512AndChecksAgainstRfc4231Vector() {
        // RFC 4231 test case 1: key 0x0b repeated 20 times, data "Hi There".
        String key = "\u000b".repeat(20);
        String expected = "87aa7cdea5ef619d4ff0b4241a1d6cb0"+
            "2379f4e2ce4ec2787ad0b30545e17cde"+
            "daa833b7d6b8a702038b274eaea3f4e4"+
            "be9d914eeb61f1702e696c203a126854";
        assertThat(VnPaySignature.hmacSha512Hex("Hi There", key)).isEqualTo(expected);
    }

    @Test void rejectsTamperedMalformedAndDuplicateSignatureFields() {
        var signed = new LinkedHashMap<String, List<String>>();
        signed.put("vnp_TmnCode", List.of("DEMO"));
        signed.put("vnp_Amount", List.of("125000"));
        signed.put("vnp_SecureHash", List.of(VnPaySignature.hmacSha512Hex("vnp_Amount=125000&vnp_TmnCode=DEMO", "secret")));

        assertThat(VnPaySignature.isValid(signed, "secret")).isTrue();
        signed.put("vnp_Amount", List.of("125001"));
        assertThat(VnPaySignature.isValid(signed, "secret")).isFalse();
        signed.put("vnp_Amount", List.of("125000"));
        signed.put("vnp_SecureHash", List.of("not-hex"));
        assertThat(VnPaySignature.isValid(signed, "secret")).isFalse();
        signed.put("vnp_SecureHash", List.of("00".repeat(64), "00".repeat(64)));
        assertThat(VnPaySignature.isValid(signed, "secret")).isFalse();
    }
}
