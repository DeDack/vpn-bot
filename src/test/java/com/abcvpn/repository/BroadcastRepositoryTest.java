package com.abcvpn.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.abcvpn.enumeration.BroadcastSegment;

class BroadcastRepositoryTest {
    @Test void selectedSegmentsAreCombinedAndRecipientsAreDeduplicated() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<BroadcastRepository.Audience>>any(),eq(false),eq(true)))
                .thenReturn(new BroadcastRepository.Audience(8,2));
        BroadcastRepository repository=new BroadcastRepository(jdbc);

        repository.audience(Set.of(BroadcastSegment.ACTIVE_TRIAL_NO_VPN,BroadcastSegment.TRIAL_ENDING_24H),true);

        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),org.mockito.ArgumentMatchers.<RowMapper<BroadcastRepository.Audience>>any(),eq(false),eq(true));
        assertThat(sql.getValue()).contains("COUNT(DISTINCT u.telegram_id)"," OR ","marketing_opt_out","vpn_connected");
    }

    @Test void queueReliesOnUniqueConstraintForRetrySafety() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT INTO broadcast_deliveries"),any(Object[].class))).thenReturn(4);
        BroadcastRepository repository=new BroadcastRepository(jdbc);

        repository.queue(7,Set.of(BroadcastSegment.ALL),false,false);

        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),eq(7L),eq(false),eq(false));
        assertThat(sql.getValue()).contains("ON CONFLICT DO NOTHING");
    }
}
