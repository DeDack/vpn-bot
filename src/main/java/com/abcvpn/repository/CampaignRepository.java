package com.abcvpn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.entity.Campaign;

@Repository
public class CampaignRepository {
    private final JdbcTemplate jdbcTemplate;
    public CampaignRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<Campaign> findEnabledByCode(String code) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM campaigns WHERE code=? AND enabled=TRUE", this::map, code)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }
    public List<Campaign> findAll() { return jdbcTemplate.query("SELECT * FROM campaigns ORDER BY created_at DESC", this::map); }
    public long create(String code,String name,String comment) {
        return jdbcTemplate.queryForObject("INSERT INTO campaigns(code,name,comment) VALUES(?,?,?) RETURNING id", Long.class, code,name,comment);
    }
    public void update(long id,String name,String comment,boolean enabled) {
        jdbcTemplate.update("UPDATE campaigns SET name=?,comment=?,enabled=? WHERE id=?",name,comment,enabled,id);
    }
    public CampaignStats stats(long campaignId) {
        String sql = """
                SELECT COUNT(DISTINCT u.telegram_id) users,
                    COUNT(DISTINCT u.telegram_id) FILTER (WHERE u.trial_used) trials,
                    COUNT(DISTINCT p.telegram_id) FILTER (WHERE p.status='PAID') paid_users,
                    COUNT(p.id) FILTER (WHERE p.status='PAID') payments,
                    COALESCE(SUM(p.paid_amount_rub) FILTER (WHERE p.status='PAID'),0) revenue
                FROM users u LEFT JOIN payments p ON p.telegram_id=u.telegram_id WHERE u.campaign_id=?
                """;
        return jdbcTemplate.queryForObject(sql, (rs,n) -> {
            long users=rs.getLong("users"),trials=rs.getLong("trials"),paid=rs.getLong("paid_users");
            return new CampaignStats(users,trials,paid,rs.getLong("payments"),rs.getBigDecimal("revenue"),
                    users==0?0:(trials*100.0/users),users==0?0:(paid*100.0/users));
        }, campaignId);
    }
    public List<CampaignAnalytics> analytics() {
        String sql="""
                SELECT c.id,c.code,c.name,c.enabled,
                    COUNT(DISTINCT u.telegram_id) users,
                    COUNT(DISTINCT u.telegram_id) FILTER (WHERE u.trial_used) trials,
                    COUNT(DISTINCT u.telegram_id) FILTER (WHERE s.revoked=FALSE AND (s.expires_at IS NULL OR s.expires_at>NOW())) active_users,
                    COUNT(DISTINCT p.telegram_id) FILTER (WHERE p.status='PAID') paid_users,
                    COALESCE(SUM(p.paid_amount_rub) FILTER (WHERE p.status='PAID'),0) revenue
                FROM campaigns c
                LEFT JOIN users u ON u.campaign_id=c.id
                LEFT JOIN subscriptions s ON s.telegram_id=u.telegram_id
                LEFT JOIN payments p ON p.telegram_id=u.telegram_id
                GROUP BY c.id,c.code,c.name,c.enabled,c.created_at
                ORDER BY c.created_at,c.id
                """;
        return jdbcTemplate.query(sql,(rs,n)->{
            long users=rs.getLong("users"),trials=rs.getLong("trials"),active=rs.getLong("active_users"),paid=rs.getLong("paid_users");
            return new CampaignAnalytics(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getBoolean("enabled"),
                    users,trials,active,paid,rs.getBigDecimal("revenue"),percent(trials,users),percent(active,users),percent(paid,users));
        });
    }
    private double percent(long value,long total) {return total==0?0:Math.round(value*1000.0/total)/10.0;}
    private Campaign map(ResultSet rs,int rowNum)throws SQLException { return new Campaign(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getString("comment"),rs.getBoolean("enabled"),rs.getTimestamp("created_at").toInstant()); }
    public record CampaignStats(long users,long trials,long paidUsers,long payments,java.math.BigDecimal revenue,
                                double trialConversion,double paymentConversion) {}
    public record CampaignAnalytics(long id,String code,String name,boolean enabled,long users,long trials,
                                    long activeUsers,long paidUsers,java.math.BigDecimal revenue,
                                    double trialConversion,double activeConversion,double paymentConversion) {}
}
