package com.abcvpn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.entity.Payment;
import com.abcvpn.enumeration.PaymentStatus;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbcTemplate;
    public PaymentRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public void create(Payment payment) {
        String sql = """
                INSERT INTO payments(id,telegram_id,tariff_id,amount_rub,wallet_reserved_kopecks,status,creation_uncertain,
                    terms_version,privacy_version,accepted_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbcTemplate.update(sql, payment.id(), payment.telegramId(), payment.tariffId(), payment.amountRub(),payment.walletReservedKopecks(),
                payment.status().name(), payment.creationUncertain(), payment.termsVersion(), payment.privacyVersion(),
                Timestamp.from(payment.acceptedAt()), Timestamp.from(payment.createdAt()));
    }

    public Optional<Payment> findById(UUID id) { return findOne("SELECT * FROM payments WHERE id=?", id); }
    public Optional<Payment> findByTransactionId(UUID id) { return findOne("SELECT * FROM payments WHERE platega_transaction_id=?", id); }
    public Optional<Payment> findByTransactionIdForUpdate(UUID id) { return findOne("SELECT * FROM payments WHERE platega_transaction_id=? FOR UPDATE", id); }

    public Optional<Payment> findReusable(long telegramId, long tariffId, Instant now) {
        String sql = """
                SELECT * FROM payments WHERE telegram_id=? AND tariff_id=? AND status='PENDING'
                    AND (creation_uncertain=TRUE OR payment_expires_at > ?)
                ORDER BY created_at DESC LIMIT 1
                """;
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::map, telegramId, tariffId, Timestamp.from(now))); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public void markCreationUncertain(UUID id) {
        jdbcTemplate.update("UPDATE payments SET creation_uncertain=TRUE WHERE id=? AND status='PENDING'", id);
    }

    public void attachProviderData(UUID id, UUID transactionId, String url, Instant expiresAt) {
        String sql = """
                UPDATE payments SET platega_transaction_id=?,payment_url=?,payment_expires_at=?,creation_uncertain=FALSE
                WHERE id=? AND status='PENDING'
                """;
        jdbcTemplate.update(sql, transactionId, url, Timestamp.from(expiresAt), id);
    }

    public void markCreationFailed(UUID id) {
        jdbcTemplate.update("UPDATE payments SET status='CANCELED',creation_uncertain=FALSE WHERE id=? AND status='PENDING'",id);
    }

    public boolean markPaid(UUID id, java.math.BigDecimal paidAmount, Instant paidAt) {
        return jdbcTemplate.update("""
                UPDATE payments SET status='PAID',paid_amount_rub=?,paid_at=? WHERE id=? AND status='PENDING'
                """, paidAmount, Timestamp.from(paidAt), id) == 1;
    }

    public void markCanceled(UUID id) {
        jdbcTemplate.update("UPDATE payments SET status='CANCELED' WHERE id=? AND status='PENDING'", id);
    }

    public void markChargeback(UUID id) {
        jdbcTemplate.update("UPDATE payments SET status='CHARGEBACK' WHERE id=? AND status='PAID'", id);
    }

    public boolean hasPaidPayment(long telegramId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments WHERE telegram_id=? AND status='PAID'", Integer.class, telegramId);
        return count != null && count > 0;
    }

    public List<Payment> findRecent(int limit) {
        return jdbcTemplate.query("SELECT * FROM payments ORDER BY created_at DESC LIMIT ?", this::map, limit);
    }

    public List<Payment> findForReconciliation(Instant now,int limit) {
        String sql="""
                SELECT * FROM payments WHERE status='PENDING' AND platega_transaction_id IS NOT NULL
                    AND (creation_uncertain=TRUE OR payment_expires_at <= ?)
                ORDER BY created_at LIMIT ?
                """;
        return jdbcTemplate.query(sql,this::map,Timestamp.from(now),limit);
    }

    private Optional<Payment> findOne(String sql, Object value) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::map, value)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }
    private Instant instant(ResultSet rs, String name) throws SQLException { Timestamp value=rs.getTimestamp(name); return value==null?null:value.toInstant(); }
    private Payment map(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(rs.getObject("id",UUID.class), rs.getLong("telegram_id"), rs.getLong("tariff_id"),
                rs.getObject("platega_transaction_id",UUID.class), rs.getBigDecimal("amount_rub"),
                rs.getBigDecimal("paid_amount_rub"),rs.getLong("wallet_reserved_kopecks"),PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("payment_url"), instant(rs,"payment_expires_at"), rs.getBoolean("creation_uncertain"),
                rs.getString("terms_version"), rs.getString("privacy_version"), instant(rs,"accepted_at"),
                instant(rs,"created_at"), instant(rs,"paid_at"));
    }
}
