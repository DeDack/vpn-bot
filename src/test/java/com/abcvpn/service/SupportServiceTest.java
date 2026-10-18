package com.abcvpn.service;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.entity.User;
import com.abcvpn.enumeration.SourceType;
import com.abcvpn.repository.SupportMessageRepository;
import com.abcvpn.telegram.TelegramClient;

class SupportServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test
    void userMessageIsCopiedAndRouteIsSaved() throws Exception {
        SupportMessageRepository repository=mock(SupportMessageRepository.class);TelegramClient client=mock(TelegramClient.class);
        when(repository.claim(100,10,5,"USER_TO_ADMIN")).thenReturn(true);
        when(client.send(eq(99L),anyString(),isNull())).thenReturn(20);
        when(client.copyMessage(99,10,5,20)).thenReturn(21);
        SupportService service=new SupportService(repository,client,properties());

        service.forward(100,mapper.readTree("{\"message_id\":5,\"date\":1791662400}"),user(10));

        verify(repository).attachAdminMessage(100,21);
        verify(client).send(10,"💬 Сообщение отправлено в поддержку. Ответим здесь, в чате",null);
    }

    @Test
    void adminReplyUsesPersistedRoute() throws Exception {
        SupportMessageRepository repository=mock(SupportMessageRepository.class);TelegramClient client=mock(TelegramClient.class);
        when(repository.findUserByAdminMessage(21)).thenReturn(OptionalLong.of(10));
        when(repository.claim(101,10,30,"ADMIN_TO_USER")).thenReturn(true);
        SupportService service=new SupportService(repository,client,properties());

        service.reply(101,mapper.readTree("{\"message_id\":30,\"reply_to_message\":{\"message_id\":21}}"));

        verify(client).copyMessage(10,99,30,null);
        verify(client).send(99,"✅ Ответ доставлен пользователю 10",null);
    }

    @Test
    void duplicateUpdateIsIgnored() throws Exception {
        SupportMessageRepository repository=mock(SupportMessageRepository.class);TelegramClient client=mock(TelegramClient.class);
        when(repository.claim(100,10,5,"USER_TO_ADMIN")).thenReturn(false);

        new SupportService(repository,client,properties()).forward(100,
                mapper.readTree("{\"message_id\":5,\"date\":1791662400,\"photo\":[{\"file_id\":\"x\"}]}"),user(10));

        verifyNoInteractions(client);
    }

    private User user(long id) {
        return new User(id,"user","Имя",false,SourceType.ORGANIC,null,null,"ref",false,Instant.now(),Instant.now());
    }
    private AbcVpnProperties properties() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("token",99,"bot",true),
                new AbcVpnProperties.Remnawave("","","",""),"","","@support","","","1","1",
                "password-123","session-secret-123",2,7);
    }
}
