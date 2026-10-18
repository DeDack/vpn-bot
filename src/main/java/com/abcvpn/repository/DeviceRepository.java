package com.abcvpn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.entity.Device;
import com.abcvpn.enumeration.DeviceStatus;

@Repository
public class DeviceRepository {
    private final JdbcTemplate jdbcTemplate;

    public DeviceRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate=jdbcTemplate; }

    public List<Device> findByUserId(long userId) {
        return jdbcTemplate.query("SELECT * FROM devices WHERE user_id=? ORDER BY first_seen_at",this::map,userId);
    }

    public Optional<Device> findByIdAndUserId(long id,long userId) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM devices WHERE id=? AND user_id=?",this::map,id,userId)); }
        catch(EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public Optional<Device> findByUserIdAndHwid(long userId,String hwid) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM devices WHERE user_id=? AND hwid=?",this::map,userId,hwid)); }
        catch(EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public int countActive(long userId) {
        Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM devices WHERE user_id=? AND status='ACTIVE'",Integer.class,userId);
        return count==null?0:count;
    }

    public Device upsertSeen(long userId,String hwid,String model,String os,String osVersion,DeviceStatus status,Instant now) {
        String sql="""
                INSERT INTO devices(user_id,hwid,device_model,device_os,os_version,status,first_seen_at,last_seen_at)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(user_id,hwid) DO UPDATE SET
                    device_model=COALESCE(EXCLUDED.device_model,devices.device_model),
                    device_os=COALESCE(EXCLUDED.device_os,devices.device_os),
                    os_version=COALESCE(EXCLUDED.os_version,devices.os_version),last_seen_at=EXCLUDED.last_seen_at
                RETURNING *
                """;
        return jdbcTemplate.queryForObject(sql,this::map,userId,hwid,model,os,osVersion,status.name(),Timestamp.from(now),Timestamp.from(now));
    }

    public boolean setStatus(long id,long userId,DeviceStatus status) {
        return jdbcTemplate.update("UPDATE devices SET status=?,last_seen_at=NOW() WHERE id=? AND user_id=? AND status<>?",status.name(),id,userId,status.name())>0;
    }

    public void deleteAll(long userId) { jdbcTemplate.update("DELETE FROM devices WHERE user_id=?",userId); }

    private Device map(ResultSet rs,int rowNum) throws SQLException {
        return new Device(rs.getLong("id"),rs.getLong("user_id"),rs.getString("hwid"),rs.getString("device_model"),
                rs.getString("device_os"),rs.getString("os_version"),DeviceStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("first_seen_at").toInstant(),rs.getTimestamp("last_seen_at").toInstant());
    }
}
