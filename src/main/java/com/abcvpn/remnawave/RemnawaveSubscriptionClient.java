package com.abcvpn.remnawave;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;

@Component
public class RemnawaveSubscriptionClient {
    private static final Set<String> HOP_BY_HOP=Set.of("connection","keep-alive","proxy-authenticate","proxy-authorization","te","trailers","transfer-encoding","upgrade","content-length");
    private final RestClient client;

    public RemnawaveSubscriptionClient(AbcVpnProperties properties) {
        this.client=RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory())
                .baseUrl(properties.remnawave().subscriptionBaseUrl()).build();
    }

    public ProxyResponse get(String token,HttpHeaders incoming,String remoteAddress) {
        try {
            return client.get().uri("/api/sub/"+token).headers(headers->copyRequestHeaders(incoming,headers,remoteAddress))
                    .exchange((request,response)->new ProxyResponse(response.getStatusCode(),copyResponseHeaders(response.getHeaders()),response.getBody().readAllBytes()));
        } catch(RuntimeException exception) {
            throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,"Не удалось получить подписку Remnawave",exception);
        }
    }

    private void copyRequestHeaders(HttpHeaders source,HttpHeaders target,String remoteAddress) {
        copy(source,target,HttpHeaders.ACCEPT);copy(source,target,HttpHeaders.USER_AGENT);
        copy(source,target,"x-hwid");copy(source,target,"x-device-model");copy(source,target,"x-device-os");copy(source,target,"x-ver-os");
        target.set(HttpHeaders.HOST,"sub.abc-vpn.example.com");target.set("X-Forwarded-Host","sub.abc-vpn.example.com");
        target.set("X-Forwarded-Proto","https");target.set("X-Forwarded-For",remoteAddress);
    }
    private void copy(HttpHeaders source,HttpHeaders target,String name) {List<String> values=source.get(name);if(values!=null) target.put(name,values);}
    private HttpHeaders copyResponseHeaders(HttpHeaders source) {
        HttpHeaders result=new HttpHeaders();source.forEach((name,values)->{if(!HOP_BY_HOP.contains(name.toLowerCase())) result.put(name,values);});return result;
    }
    public record ProxyResponse(HttpStatusCode status,HttpHeaders headers,byte[] body) {}
}
