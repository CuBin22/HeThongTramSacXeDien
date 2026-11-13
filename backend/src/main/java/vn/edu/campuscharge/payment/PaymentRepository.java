package vn.edu.campuscharge.payment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.campuscharge.api.ApiAccess;

@Repository
@org.springframework.context.annotation.Profile("!simulator")
public class PaymentRepository {
    public record Charge(UUID userId, String status, BigDecimal amount) {}
    public record PaymentView(UUID paymentId, UUID sessionId, String reference, BigDecimal amountVnd, String status,
                              String providerTransactionId, String redirectUrl) {}
    public record CallbackPayment(UUID paymentId, BigDecimal amountVnd, String status, String providerTransactionId) {}
    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Charge lockCharge(UUID sessionId) {
        var rows=jdbc.query("""
            SELECT b.user_id,cs.status,greatest(s.amount_due_vnd-coalesce((
                SELECT sum(p.amount_vnd) FROM payment_transactions p WHERE p.session_id=cs.id AND p.status='SUCCEEDED'
            ),0),0) AS amount_due_vnd
            FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id
            JOIN session_settlements s ON s.session_id=cs.id WHERE cs.id=? FOR UPDATE OF cs
            """,
            (r,n)->new Charge(r.getObject("user_id",UUID.class),r.getString("status"),r.getBigDecimal("amount_due_vnd")),sessionId);
        if(rows.isEmpty()) throw ApiAccess.missing();
        return rows.getFirst();
    }

    public Optional<PaymentView> bySessionKey(UUID sessionId,String key) {
        return one("SELECT * FROM payment_transactions WHERE session_id=? AND idempotency_key=?",sessionId,key);
    }
    public Optional<PaymentView> activeForSession(UUID sessionId) {
        return one("SELECT * FROM payment_transactions WHERE session_id=? AND status IN ('PENDING','SUCCEEDED') ORDER BY created_at DESC LIMIT 1",sessionId);
    }
    public List<PaymentView> listForUser(UUID userId) {
        return jdbc.query("""
            SELECT DISTINCT ON (p.session_id) p.* FROM payment_transactions p
            JOIN charging_sessions cs ON cs.id=p.session_id JOIN bookings b ON b.id=cs.booking_id
            WHERE b.user_id=? ORDER BY p.session_id,p.created_at DESC,p.id DESC
            """,(r,n)->view(r),userId);
    }
    public PaymentView insert(UUID sessionId,String ref,BigDecimal amount,String key,String url) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO payment_transactions(id,session_id,transaction_reference,amount_vnd,idempotency_key,redirect_url) VALUES (?,?,?,?,?,?)",
            id,sessionId,ref,amount,key,url);
        return byId(id);
    }
    public PaymentView getOwned(UUID id,UUID userId) {
        var rows=jdbc.query("SELECT p.*,b.user_id FROM payment_transactions p JOIN charging_sessions cs ON cs.id=p.session_id JOIN bookings b ON b.id=cs.booking_id WHERE p.id=?",
            (r,n)->new AbstractMap.SimpleImmutableEntry<>(view(r),r.getObject("user_id",UUID.class)),id);
        if(rows.isEmpty()) throw ApiAccess.missing();
        ApiAccess.owner(rows.getFirst().getValue(),userId);
        return rows.getFirst().getKey();
    }
    public PaymentView byReferenceForUser(String ref,UUID userId) {
        var rows=jdbc.query("SELECT p.*,b.user_id FROM payment_transactions p JOIN charging_sessions cs ON cs.id=p.session_id JOIN bookings b ON b.id=cs.booking_id WHERE p.transaction_reference=?",
            (r,n)->new AbstractMap.SimpleImmutableEntry<>(view(r),r.getObject("user_id",UUID.class)),ref);
        if(rows.isEmpty()) throw ApiAccess.missing();
        ApiAccess.owner(rows.getFirst().getValue(),userId);
        return rows.getFirst().getKey();
    }
    public CallbackPayment lockByReference(String ref) {
        var rows=jdbc.query("SELECT id,amount_vnd,status,provider_transaction_id FROM payment_transactions WHERE transaction_reference=? FOR UPDATE",
            (r,n)->new CallbackPayment(r.getObject("id",UUID.class),r.getBigDecimal("amount_vnd"),r.getString("status"),r.getString("provider_transaction_id")),ref);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean providerTransactionUsedByAnother(UUID paymentId,String txn) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_transactions WHERE provider_transaction_id=? AND id<>?)",Boolean.class,txn,paymentId));
    }
    public int succeed(UUID id,String txn) {
        return jdbc.update("UPDATE payment_transactions SET status='SUCCEEDED',provider_transaction_id=?,paid_at=now() WHERE id=? AND status='PENDING'",txn,id);
    }
    public int finish(UUID id,String txn,String status) {
        return jdbc.update("UPDATE payment_transactions SET status=?,provider_transaction_id=? WHERE id=? AND status='PENDING'",status,txn,id);
    }
    public PaymentView byId(UUID id) { return one("SELECT * FROM payment_transactions WHERE id=?",id).orElseThrow(ApiAccess::missing); }
    private Optional<PaymentView> one(String sql,Object... args) {
        var rows=jdbc.query(sql,(r,n)->view(r),args);
        return rows.stream().findFirst();
    }
    private static PaymentView view(ResultSet r) throws SQLException {
        return new PaymentView(r.getObject("id",UUID.class),r.getObject("session_id",UUID.class),r.getString("transaction_reference"),
            r.getBigDecimal("amount_vnd"),r.getString("status"),r.getString("provider_transaction_id"),r.getString("redirect_url"));
    }
}
