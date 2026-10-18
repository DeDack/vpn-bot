package com.abcvpn.remnawave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.abcvpn.config.AbcVpnProperties;

class RemnawaveClientTest {
    MockRestServiceServer server;RemnawaveClient client;UUID squad=UUID.randomUUID();
    @BeforeEach void setUp(){RestClient.Builder builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();client=new RemnawaveClient(builder,props());}
    @Test void createsUserWithSquadAndReadsStableUrl(){
        server.expect(requestTo("https://remna.test/api/users")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value("tg_10")).andExpect(jsonPath("$.activeInternalSquads[0]").value(squad.toString())).andExpect(jsonPath("$.hwidDeviceLimit").value(100))
                .andExpect(jsonPath("$.expireAt").value("2026-02-01T00:00:00Z"))
                .andRespond(withSuccess(response("1","https://sub.test/key","ACTIVE"),MediaType.APPLICATION_JSON));
        var user=client.create(10,Instant.parse("2026-02-01T00:00:00Z"),squad,5);
        assertThat(user.id()).isEqualTo("1");assertThat(user.subscriptionUrl()).isEqualTo("https://sub.test/key");
    }
    @Test void truncatesExpiryToMillisecondsForRemnawave(){
        server.expect(requestTo("https://remna.test/api/users")).andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.expireAt").value("2026-03-01T00:00:00.123Z"))
                .andRespond(withSuccess(response("1","https://sub.test/key","ACTIVE"),MediaType.APPLICATION_JSON));

        client.update("1",Instant.parse("2026-03-01T00:00:00.123456Z"),squad,10);

        server.verify();
    }
    @Test void findsUpdatesEnablesAndDisablesUserUsingOfficialEndpoints(){
        server.expect(requestTo("https://remna.test/api/users/by-username/tg_10")).andRespond(withSuccess(response("1","https://sub.test/key","ACTIVE"),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remna.test/api/users")).andExpect(method(HttpMethod.PATCH)).andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.hwidDeviceLimit").value(100))
                .andExpect(jsonPath("$.activeInternalSquads[0]").value(squad.toString())).andRespond(withSuccess(response("1","https://sub.test/key","ACTIVE"),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remna.test/api/users/1/actions/enable")).andRespond(withSuccess(response("1","https://sub.test/key","ACTIVE"),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remna.test/api/users/1/actions/disable")).andRespond(withSuccess(response("1","https://sub.test/key","DISABLED"),MediaType.APPLICATION_JSON));
        assertThat(client.findByUsername("tg_10")).isNotNull();client.update("1",Instant.parse("2026-03-01T00:00:00Z"),squad,5);client.enable("1");client.disable("1");server.verify();
    }
    @Test void deletesUserUsingOfficialEndpoint(){
        server.expect(requestTo("https://remna.test/api/users/1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent());
        client.delete("1");
        server.verify();
    }
    @Test void managesHwidDevicesUsingUserId(){
        server.expect(requestTo("https://remna.test/api/hwid/devices")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.userId").value(1)).andExpect(jsonPath("$.hwid").value("device-hwid-123"))
                .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remna.test/api/hwid/devices/delete")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.userId").value(1)).andExpect(jsonPath("$.hwid").value("device-hwid-123"))
                .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remna.test/api/hwid/devices/delete-all")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.userId").value(1)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));

        client.addHwid("1","device-hwid-123","iOS","18","iPhone");
        client.deleteHwid("1","device-hwid-123");client.deleteAllHwid("1");server.verify();
    }
    @Test void readsUserHwidDevices(){
        server.expect(requestTo("https://remna.test/api/hwid/devices/1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"response":{"total":1,"devices":[{"hwid":"device-hwid-123","userId":1,
                        "platform":"iOS","osVersion":"18","deviceModel":"iPhone"}]}}
                        """,MediaType.APPLICATION_JSON));

        var devices=client.getHwidDevices("1");

        assertThat(devices).hasSize(1);assertThat(devices.getFirst().deviceModel()).isEqualTo("iPhone");
    }
    @Test void countsVpnUseOnlyFromConfirmedTraffic(){
        server.expect(requestTo("https://remna.test/api/users?start=0&size=1000")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"response":{"users":[
                          {"telegramId":10,"userTraffic":{"lifetimeUsedTrafficBytes":2048,"firstConnectedAt":"2026-10-10T10:00:00Z"}},
                          {"telegramId":11,"userTraffic":{"lifetimeUsedTrafficBytes":0,"firstConnectedAt":null}}
                        ]}}
                        """,MediaType.APPLICATION_JSON));

        var connected=client.connectedUsers();

        assertThat(connected).containsExactly(new RemnawaveClient.ConnectedUser(10,Instant.parse("2026-10-10T10:00:00Z")));
    }
    private String response(String id,String url,String status){return "{\"response\":{\"id\":"+id+",\"subscriptionUrl\":\""+url+"\",\"status\":\""+status+"\"}}";}
    private AbcVpnProperties props(){return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"bot",false),new AbcVpnProperties.Remnawave("https://remna.test","https://remna.test","token",""),"https://vpn.test","https://api.vpn.test","@s","p","t","1","1","password-123","session-secret-123",1,3);}
}
