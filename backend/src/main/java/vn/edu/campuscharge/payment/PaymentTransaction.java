package vn.edu.campuscharge.payment;

import java.util.Objects;
import java.util.UUID;

/** VNPay sandbox transaction. Application code must verify IPN signature/reference/amount before mutation. */
public final class PaymentTransaction {
    private final UUID id;
    private final UUID sessionId;
    private final String reference;
    private final Money amount;
    private PaymentStatus status = PaymentStatus.PENDING;
    private String providerTransactionId;

    public PaymentTransaction(UUID id, UUID sessionId, String reference, Money amount) {
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Payment reference is required");
        this.reference = reference;
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    public void succeed(String providerTransactionId) {
        if (providerTransactionId == null || providerTransactionId.isBlank())
            throw new IllegalArgumentException("Provider transaction ID is required");
        if (status == PaymentStatus.SUCCEEDED && providerTransactionId.equals(this.providerTransactionId)) return;
        requirePending();
        this.providerTransactionId = providerTransactionId;
        status = PaymentStatus.SUCCEEDED;
    }

    public void fail() { requirePending(); status = PaymentStatus.FAILED; }
    public void cancel() { requirePending(); status = PaymentStatus.CANCELLED; }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) throw new IllegalStateException("Payment is already " + status);
    }

    public UUID id() { return id; }
    public UUID sessionId() { return sessionId; }
    public String reference() { return reference; }
    public Money amount() { return amount; }
    public PaymentStatus status() { return status; }
    public String providerTransactionId() { return providerTransactionId; }
}
