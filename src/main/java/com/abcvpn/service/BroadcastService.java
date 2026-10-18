package com.abcvpn.service;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Broadcast;
import com.abcvpn.enumeration.BroadcastSegment;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.BroadcastRepository;
import com.abcvpn.telegram.TelegramClient;

@Service
public class BroadcastService {
    private static final Set<String> TEMPLATES=Set.of("CONNECT_REMINDER","TRIAL_LAST_DAY","RETURN_BONUS","PAID_OFFER","SERVICE_UPDATE");
    private final BroadcastRepository repository;private final TelegramClient telegramClient;
    public BroadcastService(BroadcastRepository repository,TelegramClient telegramClient) {this.repository=repository;this.telegramClient=telegramClient;}

    public BroadcastPage page() {return new BroadcastPage(repository.history());}
    public BroadcastRepository.Audience audience(Set<BroadcastSegment> segments,boolean marketing) {return repository.audience(segments,marketing);}
    @Transactional
    public Draft preview(String template,String text,String buttonText,String buttonType,String buttonValue,
                         boolean marketing,Set<BroadcastSegment> segments) {
        validate(template,text,buttonText,buttonType,buttonValue,segments);
        long id=repository.create(template,text,blank(buttonText),blank(buttonType),blank(buttonValue),marketing);
        repository.addSegments(id,segments);
        return new Draft(repository.find(id),segments,repository.audience(segments,marketing,"RETURN_BONUS".equals(template)));
    }
    public Draft draft(long id) {
        Broadcast broadcast=repository.find(id);Set<BroadcastSegment> segments=Set.copyOf(repository.segments(id));
        return new Draft(broadcast,segments,repository.audience(segments,broadcast.marketing(),"RETURN_BONUS".equals(broadcast.templateType())));
    }
    public void sendTest(long id) {
        Broadcast broadcast=repository.find(id);
        telegramClient.sendAdmin("🧪 <b>Тест рассылки</b>\n\n"+broadcast.messageText(),keyboard(broadcast));
    }
    @Transactional
    public int confirm(long id) {
        Broadcast broadcast=repository.findForUpdate(id);
        if(!"DRAFT".equals(broadcast.status())) throw new FlowException(ErrorCode.INVALID_REQUEST,"Рассылка уже подтверждена");
        Set<BroadcastSegment> segments=Set.copyOf(repository.segments(id));
        return repository.queue(id,segments,broadcast.marketing(),"RETURN_BONUS".equals(broadcast.templateType()));
    }
    public TelegramClient.Keyboard keyboard(BroadcastRepository.Delivery delivery) {
        java.util.ArrayList<TelegramClient.Row> rows=new java.util.ArrayList<>();
        addButton(rows,delivery.buttonText(),delivery.buttonType(),delivery.buttonValue());
        return rows.isEmpty()?null:new TelegramClient.Keyboard(rows);
    }
    private TelegramClient.Keyboard keyboard(Broadcast broadcast) {
        java.util.ArrayList<TelegramClient.Row> rows=new java.util.ArrayList<>();
        addButton(rows,broadcast.buttonText(),broadcast.buttonType(),broadcast.buttonValue());
        return rows.isEmpty()?null:new TelegramClient.Keyboard(rows);
    }
    private void addButton(List<TelegramClient.Row> rows,String text,String type,String value) {
        if(text==null||type==null||value==null) return;
        rows.add(TelegramClient.row("URL".equals(type)?TelegramClient.url(text,value):TelegramClient.callback(text,value)));
    }
    private void validate(String template,String text,String buttonText,String buttonType,String buttonValue,Set<BroadcastSegment> segments) {
        if(!TEMPLATES.contains(template)||text==null||text.isBlank()||text.length()>4000||segments==null||segments.isEmpty())
            throw new FlowException(ErrorCode.INVALID_REQUEST);
        String lower=text.toLowerCase();
        if(lower.contains("sub.abc-vpn.example.com/")||lower.contains("happ://add/"))
            throw new FlowException(ErrorCode.INVALID_REQUEST,"Персональные ссылки запрещены в рассылке");
        boolean any=buttonText!=null&&!buttonText.isBlank()||buttonType!=null&&!buttonType.isBlank()||buttonValue!=null&&!buttonValue.isBlank();
        if(!any) {
            if("RETURN_BONUS".equals(template))
                throw new FlowException(ErrorCode.INVALID_REQUEST,"Для бонуса требуется callback return_bonus");
            return;
        }
        if(buttonText==null||buttonText.isBlank()||buttonText.length()>64||buttonType==null||buttonValue==null||buttonValue.isBlank())
            throw new FlowException(ErrorCode.INVALID_REQUEST,"Заполните все поля кнопки");
        if("URL".equals(buttonType)) {
            URI uri;
            try {uri=URI.create(buttonValue);} catch(IllegalArgumentException exception) {throw new FlowException(ErrorCode.INVALID_REQUEST);}
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)
                throw new FlowException(ErrorCode.INVALID_REQUEST,"Разрешены только HTTPS-ссылки");
        } else if(!"CALLBACK".equals(buttonType)||!buttonValue.matches("[a-zA-Z0-9_:.-]{1,64}"))
            throw new FlowException(ErrorCode.INVALID_REQUEST,"Некорректный callback");
        if("RETURN_BONUS".equals(template)&&(!"CALLBACK".equals(buttonType)||!"return_bonus".equals(buttonValue)))
            throw new FlowException(ErrorCode.INVALID_REQUEST,"Для бонуса требуется callback return_bonus");
        if("RETURN_BONUS".equals(template)&&!segments.equals(Set.of(BroadcastSegment.EXPIRED_TRIAL_NO_VPN)))
            throw new FlowException(ErrorCode.INVALID_REQUEST,"Возвратный бонус доступен только сегменту истёкшего trial без VPN-трафика");
    }
    private String blank(String value) {return value==null||value.isBlank()?null:value.trim();}
    public record Draft(Broadcast broadcast,Set<BroadcastSegment> segments,BroadcastRepository.Audience audience) {}
    public record BroadcastPage(List<Broadcast> history) {}
}
