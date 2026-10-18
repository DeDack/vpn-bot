package com.abcvpn.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.entity.Broadcast;
import com.abcvpn.enumeration.BroadcastSegment;

@Repository
public class BroadcastRepository {
    private final JdbcTemplate jdbcTemplate;
    public BroadcastRepository(JdbcTemplate jdbcTemplate) {this.jdbcTemplate=jdbcTemplate;}

    public long create(String templateType,String text,String buttonText,String buttonType,String buttonValue,boolean marketing) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO broadcasts(template_type,message_text,button_text,button_type,button_value,marketing)
                VALUES(?,?,?,?,?,?) RETURNING id
                """,Long.class,templateType,text,buttonText,buttonType,buttonValue,marketing);
    }
    public void addSegments(long id,Set<BroadcastSegment> segments) {
        for(BroadcastSegment segment:segments)
            jdbcTemplate.update("INSERT INTO broadcast_segments(broadcast_id,segment) VALUES(?,?) ON CONFLICT DO NOTHING",id,segment.name());
    }
    public Broadcast find(long id) {
        return jdbcTemplate.queryForObject("SELECT * FROM broadcasts WHERE id=?",this::map,id);
    }
    public Broadcast findForUpdate(long id) {return jdbcTemplate.queryForObject("SELECT * FROM broadcasts WHERE id=? FOR UPDATE",this::map,id);}
    public List<BroadcastSegment> segments(long id) {
        return jdbcTemplate.query("SELECT segment FROM broadcast_segments WHERE broadcast_id=? ORDER BY segment",
                (rs,n)->BroadcastSegment.valueOf(rs.getString(1)),id);
    }
    public List<Broadcast> history() {
        return jdbcTemplate.query("SELECT * FROM broadcasts ORDER BY created_at DESC LIMIT 100",this::map);
    }
    public Audience audience(Set<BroadcastSegment> segments,boolean marketing) {
        return audience(segments,marketing,false);
    }
    public Audience audience(Set<BroadcastSegment> segments,boolean marketing,boolean reliableHistoryOnly) {
        String condition=segmentCondition(segments);
        String sql="""
                SELECT COUNT(DISTINCT u.telegram_id) FILTER(WHERE ?=FALSE OR
                      u.created_at >= COALESCE((SELECT dateexecuted FROM databasechangelog WHERE id='008-analytics-events' ORDER BY orderexecuted DESC LIMIT 1),NOW())) recipients,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE NOT EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='vpn_connected')
                      AND u.created_at < COALESCE((SELECT dateexecuted FROM databasechangelog WHERE id='008-analytics-events' ORDER BY orderexecuted DESC LIMIT 1),NOW())) unknown
                FROM users u LEFT JOIN subscriptions s ON s.telegram_id=u.telegram_id LEFT JOIN tariffs t ON t.id=s.tariff_id
                WHERE NOT u.is_banned AND NOT u.telegram_blocked
                  AND (?=FALSE OR (NOT u.marketing_opt_out AND NOT EXISTS(
                    SELECT 1 FROM broadcast_deliveries bd JOIN broadcasts b ON b.id=bd.broadcast_id
                    WHERE bd.telegram_id=u.telegram_id AND bd.status='SENT' AND b.marketing AND bd.sent_at>NOW()-INTERVAL '7 days')))
                  AND ("""+condition+")";
        return jdbcTemplate.queryForObject(sql,(rs,n)->new Audience(rs.getInt("recipients"),rs.getInt("unknown")),reliableHistoryOnly,marketing);
    }
    public int queue(long id,Set<BroadcastSegment> segments,boolean marketing,boolean reliableHistoryOnly) {
        String condition=segmentCondition(segments);
        String sql="""
                INSERT INTO broadcast_deliveries(broadcast_id,telegram_id)
                SELECT ?,u.telegram_id FROM users u
                LEFT JOIN subscriptions s ON s.telegram_id=u.telegram_id LEFT JOIN tariffs t ON t.id=s.tariff_id
                WHERE NOT u.is_banned AND NOT u.telegram_blocked
                  AND (?=FALSE OR u.created_at >= COALESCE((SELECT dateexecuted FROM databasechangelog WHERE id='008-analytics-events' ORDER BY orderexecuted DESC LIMIT 1),NOW()))
                  AND (?=FALSE OR (NOT u.marketing_opt_out AND NOT EXISTS(
                    SELECT 1 FROM broadcast_deliveries bd JOIN broadcasts b ON b.id=bd.broadcast_id
                    WHERE bd.telegram_id=u.telegram_id AND bd.status='SENT' AND b.marketing AND bd.sent_at>NOW()-INTERVAL '7 days')))
                  AND ("""+condition+") ON CONFLICT DO NOTHING";
        int count=jdbcTemplate.update(sql,id,reliableHistoryOnly,marketing);
        jdbcTemplate.update("UPDATE broadcasts SET status=?,recipient_count=?,confirmed_at=NOW(),completed_at=CASE WHEN ?=0 THEN NOW() ELSE NULL END WHERE id=? AND status='DRAFT'",
                count==0?"COMPLETED":"QUEUED",count,count,id);
        return count;
    }
    public List<Delivery> claim(int limit) {
        return jdbcTemplate.query("""
                WITH due AS (
                  SELECT bd.id,bd.status='SENDING' recovered FROM broadcast_deliveries bd JOIN broadcasts b ON b.id=bd.broadcast_id
                  WHERE b.status IN ('QUEUED','SENDING') AND
                    ((bd.status IN ('PENDING','RETRY') AND bd.next_attempt_at<=NOW()) OR
                     (bd.status='SENDING' AND bd.updated_at<NOW()-INTERVAL '5 minutes'))
                  ORDER BY bd.id FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE broadcast_deliveries bd SET status='SENDING',attempts=attempts+1,updated_at=NOW()
                FROM due,broadcasts b,users u WHERE bd.id=due.id AND b.id=bd.broadcast_id AND u.telegram_id=bd.telegram_id
                RETURNING bd.id,bd.broadcast_id,bd.telegram_id,bd.attempts,b.message_text,b.button_text,b.button_type,b.button_value,b.marketing,
                  due.recovered,
                  (NOT u.is_banned AND NOT u.telegram_blocked AND (NOT b.marketing OR (NOT u.marketing_opt_out AND NOT EXISTS(
                    SELECT 1 FROM broadcast_deliveries recent JOIN broadcasts rb ON rb.id=recent.broadcast_id
                    WHERE recent.telegram_id=u.telegram_id AND recent.status='SENT' AND rb.marketing
                      AND recent.sent_at>NOW()-INTERVAL '7 days')))) eligible
                """,this::delivery,limit);
    }
    public void markSent(long deliveryId,Integer messageId) {
        jdbcTemplate.update("UPDATE broadcast_deliveries SET status='SENT',telegram_message_id=?,sent_at=NOW(),updated_at=NOW(),error=NULL WHERE id=?",messageId,deliveryId);
        refresh(deliveryId);
    }
    public void markRetry(long deliveryId,Instant nextAt,String error) {
        jdbcTemplate.update("UPDATE broadcast_deliveries SET status='RETRY',next_attempt_at=?,error=?,updated_at=NOW() WHERE id=?",Timestamp.from(nextAt),shortError(error),deliveryId);
        refresh(deliveryId);
    }
    public void markFailed(long deliveryId,String error,boolean skipped) {
        jdbcTemplate.update("UPDATE broadcast_deliveries SET status=?,error=?,updated_at=NOW() WHERE id=?",skipped?"SKIPPED":"FAILED",shortError(error),deliveryId);
        refresh(deliveryId);
    }
    private void refresh(long deliveryId) {
        jdbcTemplate.update("""
                UPDATE broadcasts b SET status=CASE WHEN EXISTS(SELECT 1 FROM broadcast_deliveries d WHERE d.broadcast_id=b.id AND d.status IN ('PENDING','SENDING','RETRY')) THEN 'SENDING' ELSE 'COMPLETED' END,
                  sent_count=(SELECT COUNT(*) FROM broadcast_deliveries d WHERE d.broadcast_id=b.id AND d.status='SENT'),
                  failed_count=(SELECT COUNT(*) FROM broadcast_deliveries d WHERE d.broadcast_id=b.id AND d.status IN ('FAILED','SKIPPED')),
                  completed_at=CASE WHEN EXISTS(SELECT 1 FROM broadcast_deliveries d WHERE d.broadcast_id=b.id AND d.status IN ('PENDING','SENDING','RETRY')) THEN NULL ELSE NOW() END
                WHERE b.id=(SELECT broadcast_id FROM broadcast_deliveries WHERE id=?)
                """,deliveryId);
    }
    private String segmentCondition(Set<BroadcastSegment> segments) {
        if(segments==null||segments.isEmpty()) throw new IllegalArgumentException("Не выбран сегмент");
        if(segments.contains(BroadcastSegment.ALL)) return "TRUE";
        java.util.ArrayList<String> conditions=new java.util.ArrayList<>();
        for(BroadcastSegment segment:segments) conditions.add(switch(segment) {
            case ACTIVE_TRIAL_NO_VPN -> "t.code='TRIAL' AND NOT s.revoked AND s.expires_at>NOW() AND NOT "+vpnExists();
            case TRIAL_ENDING_24H -> "t.code='TRIAL' AND NOT s.revoked AND s.expires_at>NOW() AND s.expires_at<=NOW()+INTERVAL '24 hours'";
            case EXPIRED_TRIAL_NO_VPN -> "t.code='TRIAL' AND s.expires_at<=NOW() AND NOT "+vpnExists();
            case EXPIRED_TRIAL_VPN -> "t.code='TRIAL' AND s.expires_at<=NOW() AND "+vpnExists();
            case ACTIVE_PAID -> "t.code<>'TRIAL' AND NOT s.revoked AND (s.expires_at IS NULL OR s.expires_at>NOW()) AND EXISTS(SELECT 1 FROM payments p WHERE p.telegram_id=u.telegram_id AND p.status='PAID')";
            case ALL -> "TRUE";
        });
        return String.join(" OR ",conditions);
    }
    private String vpnExists() {return "EXISTS(SELECT 1 FROM analytics_events e WHERE e.telegram_id=u.telegram_id AND e.event_type='vpn_connected')";}
    private String shortError(String value) {if(value==null)return null;return value.substring(0,Math.min(500,value.length()));}
    private Broadcast map(ResultSet rs,int row)throws SQLException {
        return new Broadcast(rs.getLong("id"),rs.getString("template_type"),rs.getString("message_text"),rs.getString("button_text"),
                rs.getString("button_type"),rs.getString("button_value"),rs.getBoolean("marketing"),rs.getString("status"),
                rs.getInt("recipient_count"),rs.getInt("sent_count"),rs.getInt("failed_count"),instant(rs,"created_at"),instant(rs,"confirmed_at"),instant(rs,"completed_at"));
    }
    private Delivery delivery(ResultSet rs,int row)throws SQLException {return new Delivery(rs.getLong("id"),rs.getLong("broadcast_id"),rs.getLong("telegram_id"),rs.getInt("attempts"),rs.getString("message_text"),rs.getString("button_text"),rs.getString("button_type"),rs.getString("button_value"),rs.getBoolean("marketing"),rs.getBoolean("recovered"),rs.getBoolean("eligible"));}
    private Instant instant(ResultSet rs,String name)throws SQLException {Timestamp value=rs.getTimestamp(name);return value==null?null:value.toInstant();}
    public record Audience(int recipients,int unknownTelemetry) {}
    public record Delivery(long id,long broadcastId,long telegramId,int attempts,String text,String buttonText,String buttonType,String buttonValue,boolean marketing,boolean recovered,boolean eligible) {}
}
