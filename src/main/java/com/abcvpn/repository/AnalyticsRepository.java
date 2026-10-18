package com.abcvpn.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbcTemplate;
    public AnalyticsRepository(JdbcTemplate jdbcTemplate) {this.jdbcTemplate=jdbcTemplate;}

    public Summary summary(Instant cutoff) {
        String sql="""
                WITH cohort AS (SELECT * FROM users WHERE (?::timestamptz IS NULL OR created_at>=?)),
                facts AS (SELECT u.telegram_id,u.trial_used,
                    EXISTS(SELECT 1 FROM subscriptions s WHERE s.telegram_id=u.telegram_id AND NOT s.revoked AND (s.expires_at IS NULL OR s.expires_at>NOW())) active,
                    EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='one_click_redirect_opened') happ,
                    EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='subscription_link_requested') configured,
                    EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='vpn_connected') vpn,
                    EXISTS(SELECT 1 FROM payments p WHERE p.telegram_id=u.telegram_id AND p.status='PAID') paid
                    FROM cohort u)
                SELECT COUNT(*) starts,COUNT(*) FILTER(WHERE trial_used) trials,COUNT(*) FILTER(WHERE active) active,
                    COUNT(*) FILTER(WHERE happ) happ,COUNT(*) FILTER(WHERE configured) configured,COUNT(*) FILTER(WHERE vpn) vpn,
                    COUNT(*) FILTER(WHERE paid) paid,COALESCE((SELECT SUM(p.paid_amount_rub) FROM payments p JOIN cohort u ON u.telegram_id=p.telegram_id WHERE p.status='PAID'),0) revenue
                FROM facts
                """;
        Timestamp value=cutoff==null?null:Timestamp.from(cutoff);
        return jdbcTemplate.queryForObject(sql,(rs,n)->new Summary(rs.getLong("starts"),rs.getLong("trials"),rs.getLong("active"),
                rs.getLong("happ"),rs.getLong("configured"),rs.getLong("vpn"),rs.getLong("paid"),rs.getBigDecimal("revenue")),value,value);
    }

    public List<CampaignAnalytics> campaigns(Instant cutoff) {
        String sql="""
                SELECT c.id,c.code,c.name,c.enabled,COUNT(DISTINCT u.telegram_id) starts,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE u.trial_used) trials,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='one_click_redirect_opened')) happ,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='subscription_link_requested')) configured,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='vpn_connected')) vpn,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE EXISTS(SELECT 1 FROM payments p WHERE p.telegram_id=u.telegram_id AND p.status='PAID')) paid,
                    COALESCE((SELECT SUM(p.paid_amount_rub) FROM payments p JOIN users pu ON pu.telegram_id=p.telegram_id WHERE pu.campaign_id=c.id AND p.status='PAID' AND (?::timestamptz IS NULL OR pu.created_at>=?)),0) revenue
                FROM campaigns c LEFT JOIN users u ON u.campaign_id=c.id AND (?::timestamptz IS NULL OR u.created_at>=?)
                GROUP BY c.id,c.code,c.name,c.enabled,c.created_at ORDER BY c.created_at,c.id
                """;
        Timestamp value=cutoff==null?null:Timestamp.from(cutoff);
        return jdbcTemplate.query(sql,(rs,n)->campaign(rs),value,value,value,value);
    }

    public List<UserAnalytics> users(Instant cutoff) {
        String sql="""
                SELECT u.telegram_id,u.username,u.created_at,u.source_type,c.code campaign,s.expires_at,
                    (s.telegram_id IS NOT NULL AND NOT s.revoked AND (s.expires_at IS NULL OR s.expires_at>NOW())) active,
                    EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='one_click_redirect_opened') happ,
                    EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='subscription_link_requested') configured,
                    EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='vpn_connected') vpn,
                    EXISTS(SELECT 1 FROM payments p WHERE p.telegram_id=u.telegram_id AND p.status='PAID') paid
                FROM users u LEFT JOIN campaigns c ON c.id=u.campaign_id LEFT JOIN subscriptions s ON s.telegram_id=u.telegram_id
                WHERE (?::timestamptz IS NULL OR u.created_at>=?) ORDER BY u.created_at DESC LIMIT 500
                """;
        Timestamp value=cutoff==null?null:Timestamp.from(cutoff);
        return jdbcTemplate.query(sql,(rs,n)->new UserAnalytics(rs.getLong("telegram_id"),rs.getString("username"),rs.getTimestamp("created_at").toInstant(),
                rs.getString("source_type"),rs.getString("campaign"),rs.getTimestamp("expires_at")==null?null:rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("active"),rs.getBoolean("happ"),rs.getBoolean("configured"),rs.getBoolean("vpn"),rs.getBoolean("paid")),value,value);
    }

    private CampaignAnalytics campaign(ResultSet rs)throws SQLException {
        long starts=rs.getLong("starts"),happ=rs.getLong("happ"),vpn=rs.getLong("vpn"),paid=rs.getLong("paid");
        return new CampaignAnalytics(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getBoolean("enabled"),starts,
                rs.getLong("trials"),happ,rs.getLong("configured"),vpn,paid,rs.getBigDecimal("revenue"),percent(happ,starts),percent(vpn,starts),percent(paid,starts));
    }
    private double percent(long value,long total) {return total==0?0:Math.round(value*1000.0/total)/10.0;}
    public record Summary(long starts,long trials,long active,long happ,long configured,long vpn,long paid,BigDecimal revenue) {}
    public record CampaignAnalytics(long id,String code,String name,boolean enabled,long starts,long trials,long happ,long configured,long vpn,long paid,BigDecimal revenue,double happConversion,double vpnConversion,double paidConversion) {}
    public record UserAnalytics(long telegramId,String username,Instant startedAt,String source,String campaign,Instant expiresAt,boolean active,boolean happ,boolean configured,boolean vpn,boolean paid) {
        private static final DateTimeFormatter MOSCOW=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow"));
        public String startedAtMoscow() {return MOSCOW.format(startedAt);}
        public String expiresAtMoscow() {return expiresAt==null?"—":MOSCOW.format(expiresAt);}
    }
}
