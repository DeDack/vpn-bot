package com.abcvpn.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.abcvpn.config.AbcVpnProperties;

@Component
public class TelegramClient {
    private static final Logger log=LoggerFactory.getLogger(TelegramClient.class);
    private final RestClient client;
    private final boolean enabled;
    private final long adminId;

    public TelegramClient(RestClient.Builder builder,AbcVpnProperties properties) {
        String token=properties.telegram().token();
        this.enabled=properties.telegram().enabled()&&token!=null&&!token.isBlank();
        this.adminId=properties.telegram().adminId();
        this.client=builder.baseUrl("https://api.telegram.org/bot"+(token==null?"":token)).build();
    }

    public boolean enabled() { return enabled; }
    public void configureMenu() {
        if(!enabled) return;
        call("/setMyCommands",Map.of("commands",List.of(Map.of("command","start","description","Перезапустить 🔄"))));
        call("/setChatMenuButton",Map.of("menu_button",Map.of("type","commands")));
    }
    public JsonNode getUpdates(long offset) {
        if(!enabled) return null;
        return client.get().uri(uri -> uri.path("/getUpdates").queryParam("offset",offset).queryParam("timeout",25)
                .queryParam("allowed_updates","[\"message\",\"callback_query\"]").build()).retrieve().body(JsonNode.class);
    }
    public Integer send(long chatId,String text,Keyboard keyboard) {
        JsonNode response=call("/sendMessage",Map.of("chat_id",chatId,"text",text,"parse_mode","HTML",
                "disable_web_page_preview",true,"reply_markup",keyboard==null?new Keyboard(List.of()):keyboard));
        return response==null?null:response.path("result").path("message_id").asInt();
    }
    public Integer copyMessage(long chatId,long fromChatId,int messageId,Integer replyToMessageId) {
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("chat_id",chatId);body.put("from_chat_id",fromChatId);body.put("message_id",messageId);
        if(replyToMessageId!=null) body.put("reply_to_message_id",replyToMessageId);
        JsonNode response=call("/copyMessage",body);
        return response==null?null:response.path("result").path("message_id").asInt();
    }
    public void edit(long chatId,int messageId,String text,Keyboard keyboard) {
        call("/editMessageText",Map.of("chat_id",chatId,"message_id",messageId,"text",text,"parse_mode","HTML",
                "disable_web_page_preview",true,"reply_markup",keyboard));
    }
    public void answer(String callbackId,String text) {
        String answer=text==null?"":text;
        if(answer.length()>200) answer=answer.substring(0,200);
        call("/answerCallbackQuery",Map.of("callback_query_id",callbackId,"text",answer));
    }
    public void delete(long chatId,int messageId) { call("/deleteMessage",Map.of("chat_id",chatId,"message_id",messageId)); }
    public void sendQr(long chatId,String value) {
        if(!enabled) return;
        try {
            var matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,500,500);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix,"PNG",output);
            ByteArrayResource photo=new ByteArrayResource(output.toByteArray()) { @Override public String getFilename(){return "abcvpn-qr.png";} };
            var form=new LinkedMultiValueMap<String,Object>();
            form.add("chat_id",Long.toString(chatId));form.add("photo",photo);form.add("caption","QR-код подписки abc-vpn");
            form.add("reply_markup","{\"inline_keyboard\":[[{\"text\":\"⬅ Назад\",\"callback_data\":\"qr_delete\"}]]}");
            client.post().uri("/sendPhoto").contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toBodilessEntity();
        } catch(Exception exception) { log.warn("[TELEGRAM] Failed to send QR to {}",chatId); }
    }
    @Async
    public void sendSilently(long chatId,String text,Keyboard keyboard) {
        try { send(chatId,text,keyboard); } catch(RuntimeException exception) { log.warn("[TELEGRAM] Notification failed for {}",chatId); }
    }
    public void notifyAdmin(String text) { if(adminId>0) sendSilently(adminId,text,null); }
    public Integer sendAdmin(String text,Keyboard keyboard) {return adminId>0?send(adminId,text,keyboard):null;}
    private JsonNode call(String path,Object body) {
        if(!enabled) return null;
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }
    public static Keyboard keyboard(Row... rows) { return new Keyboard(List.of(rows)); }
    public static Row row(Button... buttons) { return new Row(List.of(buttons)); }
    public static Button callback(String text,String data) { return new Button(text,data,null); }
    public static Button url(String text,String url) { return new Button(text,null,url); }
    public record Keyboard(List<Row> inline_keyboard) {}
    public record Row(List<Button> buttons) {
        @com.fasterxml.jackson.annotation.JsonValue public List<Button> value(){return buttons;}
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Button(String text,String callback_data,String url) {}
}
