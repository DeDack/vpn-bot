package com.abcvpn.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.abcvpn.config.AbcVpnProperties;

class TelegramClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsNullButtonFieldsRejectedByTelegram() throws Exception {
        String callback = objectMapper.writeValueAsString(TelegramClient.callback("Start", "main"));
        String url = objectMapper.writeValueAsString(TelegramClient.url("Site", "https://abc-vpn.example.com"));

        assertTrue(callback.contains("\"callback_data\":\"main\""));
        assertFalse(callback.contains("\"url\""));
        assertTrue(url.contains("\"url\":\"https://abc-vpn.example.com\""));
        assertFalse(url.contains("\"callback_data\""));
    }

    @Test
    void limitsCallbackAnswerToTelegramMaximum() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramClient client = new TelegramClient(builder, properties());
        server.expect(requestTo("https://api.telegram.org/bottoken/answerCallbackQuery"))
                .andExpect(jsonPath("$.text").value("x".repeat(200)))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        client.answer("callback", "x".repeat(300));

        server.verify();
    }

    @Test
    void copiesMediaWithoutDownloadingIt() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        TelegramClient client=new TelegramClient(builder,properties());
        server.expect(requestTo("https://api.telegram.org/bottoken/copyMessage")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.chat_id").value(99)).andExpect(jsonPath("$.from_chat_id").value(10))
                .andExpect(jsonPath("$.message_id").value(5)).andExpect(jsonPath("$.reply_to_message_id").value(20))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":21}}",MediaType.APPLICATION_JSON));

        org.junit.jupiter.api.Assertions.assertEquals(21,client.copyMessage(99,10,5,20));
        server.verify();
    }

    @Test
    void configuresPersistentCommandMenu() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        TelegramClient client=new TelegramClient(builder,properties());
        server.expect(requestTo("https://api.telegram.org/bottoken/setMyCommands"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commands[0].command").value("start"))
                .andExpect(jsonPath("$.commands[0].description").value("Перезапустить 🔄"))
                .andRespond(withSuccess("{\"ok\":true}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.telegram.org/bottoken/setChatMenuButton"))
                .andExpect(method(HttpMethod.POST)).andExpect(jsonPath("$.menu_button.type").value("commands"))
                .andRespond(withSuccess("{\"ok\":true}",MediaType.APPLICATION_JSON));

        client.configureMenu();

        server.verify();
    }

    private AbcVpnProperties properties() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("token",0,"bot",true),
                new AbcVpnProperties.Remnawave("","","",""),"https://abc-vpn.example.com","https://api.abc-vpn.example.com","@support",
                "privacy","terms","1","1","password-123","session-secret-123",1,3);
    }
}
