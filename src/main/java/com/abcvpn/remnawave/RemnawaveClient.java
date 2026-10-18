package com.abcvpn.remnawave;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;

@Component
public class RemnawaveClient {
    private static final int TECHNICAL_HWID_LIMIT=100;
    private static final Logger log=LoggerFactory.getLogger(RemnawaveClient.class);
    private final RestClient client;

    public RemnawaveClient(RestClient.Builder builder,AbcVpnProperties properties) {
        this.client=builder.baseUrl(properties.remnawave().baseUrl())
                .defaultHeader("Authorization","Bearer "+properties.remnawave().token())
                .defaultHeader("Host","panel.abc-vpn.example.com").defaultHeader("X-Forwarded-Host","panel.abc-vpn.example.com")
                .defaultHeader("X-Forwarded-Proto","https").defaultHeader("X-Forwarded-For","127.0.0.1").build();
    }

    public RemoteUser create(long telegramId,Instant expiresAt,UUID squadId,int deviceLimit) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("username","tg_"+telegramId);
        body.put("expireAt",remnawaveInstant(expiresAt));
        body.put("status","ACTIVE");
        body.put("telegramId",telegramId);
        body.put("activeInternalSquads",List.of(squadId));
        body.put("hwidDeviceLimit",TECHNICAL_HWID_LIMIT);
        JsonNode response=exchange("POST","/api/users",body);
        return parseUser(response);
    }

    public RemoteUser update(String userId,Instant expiresAt,UUID squadId,int deviceLimit) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("id",Long.parseLong(userId));
        body.put("expireAt",remnawaveInstant(expiresAt));
        body.put("activeInternalSquads",List.of(squadId));
        body.put("hwidDeviceLimit",TECHNICAL_HWID_LIMIT);
        JsonNode response=exchange("PATCH","/api/users",body);
        return parseUser(response);
    }

    public void enable(String userId) { exchange("POST","/api/users/"+userId+"/actions/enable",null); }
    public void disable(String userId) { exchange("POST","/api/users/"+userId+"/actions/disable",null); }
    public void delete(String userId) { exchange("DELETE","/api/users/"+userId,null); }
    public void addHwid(String userId,String hwid,String platform,String osVersion,String deviceModel) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("userId",Long.parseLong(userId));body.put("hwid",hwid);
        if(platform!=null) body.put("platform",platform);
        if(osVersion!=null) body.put("osVersion",osVersion);
        if(deviceModel!=null) body.put("deviceModel",deviceModel);
        exchange("POST","/api/hwid/devices",body);
    }
    public void deleteHwid(String userId,String hwid) {
        exchange("POST","/api/hwid/devices/delete",Map.of("userId",Long.parseLong(userId),"hwid",hwid));
    }
    public void deleteAllHwid(String userId) {
        exchange("POST","/api/hwid/devices/delete-all",Map.of("userId",Long.parseLong(userId)));
    }
    public List<RemoteDevice> getHwidDevices(String userId) {
        JsonNode root=exchange("GET","/api/hwid/devices/"+userId,null);
        JsonNode response=root!=null&&root.has("response")?root.get("response"):root;
        JsonNode devices=response==null?null:response.get("devices");
        if(devices==null||!devices.isArray()) return List.of();
        java.util.ArrayList<RemoteDevice> result=new java.util.ArrayList<>();
        for(JsonNode device:devices) result.add(new RemoteDevice(text(device,"hwid"),text(device,"deviceModel"),
                text(device,"platform"),text(device,"osVersion")));
        return result;
    }
    public RemoteUser get(String userId) { return parseUser(exchange("GET","/api/users/"+userId,null)); }
    public RemoteUser findByUsername(String username) {
        try { return parseUser(exchange("GET","/api/users/by-username/"+username,null)); }
        catch(FlowException exception) {
            if(exception.getCause() instanceof HttpClientErrorException.NotFound) return null;
            throw exception;
        }
    }
    public List<ConnectedUser> connectedUsers() {
        java.util.ArrayList<ConnectedUser> result=new java.util.ArrayList<>();int start=0;
        while(true) {
            JsonNode root=exchange("GET","/api/users?start="+start+"&size=1000",null);
            JsonNode response=root!=null&&root.has("response")?root.get("response"):root;
            JsonNode users=response==null?null:response.get("users");
            if(users==null||!users.isArray()) return result;
            for(JsonNode user:users) {
                JsonNode traffic=user.path("userTraffic");long bytes=traffic.path("lifetimeUsedTrafficBytes").asLong();
                if(bytes>0&&user.hasNonNull("telegramId")&&traffic.hasNonNull("firstConnectedAt"))
                    result.add(new ConnectedUser(user.get("telegramId").asLong(),Instant.parse(traffic.get("firstConnectedAt").asText())));
            }
            if(users.size()<1000) return result;start+=users.size();
        }
    }

    private JsonNode exchange(String method,String uri,Object body) {
        try {
            if("DELETE".equals(method)) return client.delete().uri(uri).retrieve().body(JsonNode.class);
            RestClient.RequestBodySpec request=switch(method) {
                case "POST" -> client.post().uri(uri);
                case "PATCH" -> client.patch().uri(uri);
                default -> null;
            };
            if("GET".equals(method)) return client.get().uri(uri).retrieve().body(JsonNode.class);
            return (body==null?request:request.body(body)).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException exception) {
            log.warn("[REMNAWAVE] Request failed: {} {} status={}",method,uri,exception.getStatusCode().value());
            throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,"Ошибка Remnawave",exception);
        } catch (RestClientException exception) {
            log.warn("[REMNAWAVE] Request failed: {} {}",method,uri);
            throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,"Ошибка Remnawave",exception);
        }
    }

    private RemoteUser parseUser(JsonNode root) {
        if(root==null) throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,"Пустой ответ Remnawave");
        JsonNode node=root.has("response")?root.get("response"):root;
        if(node.has("user")) node=node.get("user");
        String id=text(node,"uuid");
        if(id==null) id=text(node,"id");
        String url=text(node,"subscriptionUrl");
        if(url==null) url=text(node,"subscription_url");
        String status=text(node,"status");
        if(id==null) throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,"Remnawave не вернула идентификатор пользователя");
        return new RemoteUser(id,url,status);
    }
    private String text(JsonNode node,String field) { JsonNode value=node.get(field);return value==null||value.isNull()?null:value.asText(); }
    private String remnawaveInstant(Instant value) {return value.truncatedTo(ChronoUnit.MILLIS).toString();}
    public record RemoteUser(String id,String subscriptionUrl,String status) {
        public boolean enabled() { return "ACTIVE".equalsIgnoreCase(status); }
    }
    public record RemoteDevice(String hwid,String deviceModel,String platform,String osVersion) {}
    public record ConnectedUser(long telegramId,Instant firstConnectedAt) {}
}
