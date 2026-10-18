package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.abcvpn.config.PlategaProperties;
import com.abcvpn.config.AbcVpnProperties;

class PlategaClientTest {
    MockRestServiceServer server;PlategaClient client;UUID transactionId=UUID.randomUUID();

    @BeforeEach void setUp() {
        RestClient.Builder builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        client=new PlategaClient(builder,new PlategaProperties(true,"https://app.platega.io/","merchant","secret"),props());
    }

    @Test void createsPaymentUsingOfficialContract() {
        server.expect(requestTo("https://app.platega.io/v2/transaction/process")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-MerchantId","merchant")).andExpect(header("X-Secret","secret"))
                .andExpect(jsonPath("$.paymentDetails.currency").value("RUB"))
                .andExpect(jsonPath("$.metadata.userId").value("10"))
                .andExpect(jsonPath("$.metadata.userName").value("user"))
                .andRespond(withSuccess("""
                        {"transactionId":"%s","status":"PENDING","url":"https://pay.example/id","expiresIn":"00:15:00","rate":91.2}
                        """.formatted(transactionId),MediaType.APPLICATION_JSON));
        assertThat(client.create(new BigDecimal("199"),10,"user","abc-vpn",UUID.randomUUID()).transactionId()).isEqualTo(transactionId);
        server.verify();
    }

    @Test void readsOfficialTransactionStatus() {
        server.expect(requestTo("https://app.platega.io/transaction/"+transactionId)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","status":"CONFIRMED","paymentDetails":{"amount":199,"currency":"RUB"},
                         "mechantId":"merchant","comission":1,"paymentMethod":"SBPQR","payload":"local-id"}
                        """.formatted(transactionId),MediaType.APPLICATION_JSON));
        var status=client.getStatus(transactionId);
        assertThat(status.status()).isEqualTo("CONFIRMED");assertThat(status.amount()).isEqualByComparingTo("199");
    }

    @Test void separatesBadRequest() { expectError(400);assertDeterministic(); }
    @Test void separatesUnauthorized() { expectError(401);assertDeterministic(); }
    @Test void treatsServerErrorAsUncertain() {
        expectError(500);
        assertThatThrownBy(()->client.create(BigDecimal.TEN,10,null,"abc-vpn",UUID.randomUUID()))
                .isInstanceOfSatisfying(PlategaClient.PlategaException.class,error->assertThat(error.uncertain()).isTrue());
    }
    @Test void startsWithoutCredentialsWhenDisabled() {
        RestClient.Builder builder=RestClient.builder();
        new PlategaClient(builder,new PlategaProperties(false,"https://app.platega.io/","",""),props());
    }
    @Test void rejectsMissingCredentialsWhenEnabled() {
        assertThatThrownBy(()->new PlategaClient(RestClient.builder(),new PlategaProperties(true,"https://app.platega.io/","",""),props()))
                .isInstanceOf(IllegalStateException.class);
    }

    private void expectError(int status) {
        server.expect(requestTo("https://app.platega.io/v2/transaction/process"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.valueOf(status)));
    }
    private void assertDeterministic() {
        assertThatThrownBy(()->client.create(BigDecimal.TEN,10,null,"abc-vpn",UUID.randomUUID()))
                .isInstanceOfSatisfying(PlategaClient.PlategaException.class,error->assertThat(error.uncertain()).isFalse());
    }
    private AbcVpnProperties props(){return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"bot",false),new AbcVpnProperties.Remnawave("","","",""),"https://vpn.test","https://api.vpn.test","@s","p","t","1","1","password-123","session-secret-123",1,3);}
}
