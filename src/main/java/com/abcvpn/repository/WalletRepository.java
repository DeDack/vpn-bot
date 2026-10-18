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
import com.abcvpn.entity.WalletAccount;
import com.abcvpn.entity.WalletTransaction;
import com.abcvpn.enumeration.WalletTransactionStatus;
import com.abcvpn.enumeration.WalletTransactionType;

@Repository
public class WalletRepository {
    private final JdbcTemplate jdbcTemplate;
    public WalletRepository(JdbcTemplate jdbcTemplate) {this.jdbcTemplate=jdbcTemplate;}

    public WalletAccount find(long telegramId) {
        ensure(telegramId);
        return jdbcTemplate.queryForObject("SELECT * FROM wallet_accounts WHERE telegram_id=?",this::account,telegramId);
    }
    public WalletAccount findForUpdate(long telegramId) {
        ensure(telegramId);
        return jdbcTemplate.queryForObject("SELECT * FROM wallet_accounts WHERE telegram_id=? FOR UPDATE",this::account,telegramId);
    }
    public void ensure(long telegramId) {
        jdbcTemplate.update("INSERT INTO wallet_accounts(telegram_id) VALUES(?) ON CONFLICT DO NOTHING",telegramId);
    }
    public boolean addCompleted(long telegramId,long amount,String direction,WalletTransactionType type,
                                String reference,UUID paymentId,Long referredId,String description,Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO wallet_transactions(telegram_id,amount_kopecks,direction,type,status,reference_key,
                  payment_id,referred_telegram_id,description,completed_at)
                VALUES(?,?,?,?,'COMPLETED',?,?,?,?,?) ON CONFLICT (reference_key) DO NOTHING
                """,telegramId,amount,direction,type.name(),reference,paymentId,referredId,description,Timestamp.from(now))==1;
    }
    public boolean addReserve(long telegramId,long amount,UUID paymentId,Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO wallet_transactions(telegram_id,amount_kopecks,direction,type,status,reference_key,payment_id,created_at)
                VALUES(?,?,'DEBIT','SUBSCRIPTION_PAYMENT_RESERVE','PENDING',?,?,?) ON CONFLICT (reference_key) DO NOTHING
                """,telegramId,amount,"payment:"+paymentId+":reserve",paymentId,Timestamp.from(now))==1;
    }
    public Optional<WalletTransaction> findByReference(String reference) {
        try {return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM wallet_transactions WHERE reference_key=?",this::transaction,reference));}
        catch(EmptyResultDataAccessException exception) {return Optional.empty();}
    }
    public Optional<WalletTransaction> findReserveForUpdate(UUID paymentId) {
        try {return Optional.ofNullable(jdbcTemplate.queryForObject("""
                SELECT * FROM wallet_transactions WHERE payment_id=? AND type='SUBSCRIPTION_PAYMENT_RESERVE' FOR UPDATE
                """,this::transaction,paymentId));}
        catch(EmptyResultDataAccessException exception) {return Optional.empty();}
    }
    public void credit(long telegramId,long amount) {
        jdbcTemplate.update("""
                UPDATE wallet_accounts SET available_kopecks=available_kopecks+?,
                  total_earned_kopecks=total_earned_kopecks+?,updated_at=NOW() WHERE telegram_id=?
                """,amount,amount,telegramId);
    }
    public boolean debitAvailable(long telegramId,long amount) {
        return jdbcTemplate.update("""
                UPDATE wallet_accounts SET available_kopecks=available_kopecks-?,
                  total_spent_kopecks=total_spent_kopecks+?,updated_at=NOW()
                WHERE telegram_id=? AND available_kopecks>=?
                """,amount,amount,telegramId,amount)==1;
    }
    public boolean reserve(long telegramId,long amount) {
        return jdbcTemplate.update("""
                UPDATE wallet_accounts SET available_kopecks=available_kopecks-?,reserved_kopecks=reserved_kopecks+?,updated_at=NOW()
                WHERE telegram_id=? AND available_kopecks>=?
                """,amount,amount,telegramId,amount)==1;
    }
    public void capture(long telegramId,long amount,long transactionId,Instant now) {
        int updated=jdbcTemplate.update("""
                UPDATE wallet_accounts SET reserved_kopecks=reserved_kopecks-?,
                  total_spent_kopecks=total_spent_kopecks+?,updated_at=NOW()
                WHERE telegram_id=? AND reserved_kopecks>=?
                """,amount,amount,telegramId,amount);
        if(updated!=1) throw new IllegalStateException("Недостаточный резерв кошелька");
        jdbcTemplate.update("UPDATE wallet_transactions SET status='COMPLETED',completed_at=? WHERE id=? AND status='PENDING'",
                Timestamp.from(now),transactionId);
    }
    public void release(long telegramId,long amount,long transactionId,UUID paymentId,Instant now) {
        int updated=jdbcTemplate.update("""
                UPDATE wallet_accounts SET reserved_kopecks=reserved_kopecks-?,available_kopecks=available_kopecks+?,updated_at=NOW()
                WHERE telegram_id=? AND reserved_kopecks>=?
                """,amount,amount,telegramId,amount);
        if(updated!=1) throw new IllegalStateException("Недостаточный резерв кошелька");
        jdbcTemplate.update("UPDATE wallet_transactions SET status='RELEASED',completed_at=? WHERE id=? AND status='PENDING'",
                Timestamp.from(now),transactionId);
        addCompleted(telegramId,amount,"CREDIT",WalletTransactionType.SUBSCRIPTION_PAYMENT_RELEASE,
                "payment:"+paymentId+":release",paymentId,null,"Возврат резерва",now);
    }
    public List<WalletTransaction> history(long telegramId,int limit,int offset) {
        return jdbcTemplate.query("""
                SELECT * FROM wallet_transactions WHERE telegram_id=? AND status='COMPLETED'
                  AND type<>'SUBSCRIPTION_PAYMENT_RELEASE'
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """,this::transaction,telegramId,limit,offset);
    }
    public ReferralStats referralStats(long telegramId,Instant now) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) invited,
                  COUNT(*) FILTER(WHERE s.telegram_id IS NOT NULL AND NOT s.revoked AND (s.expires_at IS NULL OR s.expires_at>?)
                    AND t.code<>'TRIAL' AND (EXISTS(SELECT 1 FROM payments p WHERE p.telegram_id=u.telegram_id AND p.status='PAID')
                      OR EXISTS(SELECT 1 FROM wallet_transactions wt WHERE wt.telegram_id=u.telegram_id
                        AND wt.status='COMPLETED' AND wt.type IN ('SUBSCRIPTION_PAYMENT','SUBSCRIPTION_PAYMENT_RESERVE')))) active_paid
                FROM users u LEFT JOIN subscriptions s ON s.telegram_id=u.telegram_id
                LEFT JOIN tariffs t ON t.id=s.tariff_id WHERE u.referrer_telegram_id=?
                """,(rs,n)->new ReferralStats(rs.getLong("invited"),rs.getLong("active_paid")),Timestamp.from(now),telegramId);
    }
    private WalletAccount account(ResultSet rs,int row)throws SQLException {
        return new WalletAccount(rs.getLong("telegram_id"),rs.getLong("available_kopecks"),rs.getLong("reserved_kopecks"),
                rs.getLong("total_earned_kopecks"),rs.getLong("total_spent_kopecks"));
    }
    private WalletTransaction transaction(ResultSet rs,int row)throws SQLException {
        Timestamp completed=rs.getTimestamp("completed_at");
        return new WalletTransaction(rs.getLong("id"),rs.getLong("telegram_id"),rs.getLong("amount_kopecks"),
                rs.getString("direction"),WalletTransactionType.valueOf(rs.getString("type")),
                WalletTransactionStatus.valueOf(rs.getString("status")),rs.getString("reference_key"),
                rs.getObject("payment_id",UUID.class),(Long)rs.getObject("referred_telegram_id"),rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),completed==null?null:completed.toInstant());
    }
    public record ReferralStats(long invited,long activePaid) {}
}
