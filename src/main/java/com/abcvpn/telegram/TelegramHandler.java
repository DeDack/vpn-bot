package com.abcvpn.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.dto.PaymentLink;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.entity.User;
import com.abcvpn.service.PaymentService;
import com.abcvpn.service.ReturnBonusService;
import com.abcvpn.service.OneClickSignature;
import com.abcvpn.service.DeviceService;
import com.abcvpn.service.ReferralService;
import com.abcvpn.service.SubscriptionService;
import com.abcvpn.service.SupportService;
import com.abcvpn.service.AnalyticsEventService;
import com.abcvpn.service.VpnAccessService;
import com.abcvpn.service.TariffService;
import com.abcvpn.service.TrialService;
import com.abcvpn.service.UserService;
import com.abcvpn.service.WalletService;

@Component
public class TelegramHandler {
    private static final Logger log=LoggerFactory.getLogger(TelegramHandler.class);
    private final TelegramClient client;private final UserService userService;private final SubscriptionService subscriptionService;
    private final TariffService tariffService;private final TrialService trialService;
    private final PaymentService paymentService;private final ReferralService referralService;private final WalletService walletService;private final DeviceService deviceService;private final SupportService supportService;private final AnalyticsEventService analytics;private final VpnAccessService vpnAccessService;private final ReturnBonusService returnBonusService;private final AbcVpnProperties properties;private final Clock clock;
    public TelegramHandler(TelegramClient client,UserService userService,SubscriptionService subscriptionService,
                           TariffService tariffService,TrialService trialService,
                           PaymentService paymentService,ReferralService referralService,WalletService walletService,DeviceService deviceService,
                           SupportService supportService,AnalyticsEventService analytics,VpnAccessService vpnAccessService,
                           ReturnBonusService returnBonusService,
                           AbcVpnProperties properties,Clock clock) {
        this.client=client;this.userService=userService;this.subscriptionService=subscriptionService;this.tariffService=tariffService;
        this.trialService=trialService;this.paymentService=paymentService;
        this.referralService=referralService;this.walletService=walletService;this.deviceService=deviceService;this.supportService=supportService;this.analytics=analytics;this.vpnAccessService=vpnAccessService;this.returnBonusService=returnBonusService;this.properties=properties;this.clock=clock;
    }

    public void handle(JsonNode update) {
        if(update.has("message")) handleMessage(update.path("update_id").asLong(),update.get("message"));
        else if(update.has("callback_query")) handleCallback(update.get("callback_query"));
    }
    private void handleMessage(long updateId,JsonNode message) {
        long chatId=message.path("chat").path("id").asLong();JsonNode from=message.path("from");
        String text=message.path("text").asText("");String[] command=text.trim().split("\\s+",2);
        String parameter=command.length==2?command[1]:null;
        long telegramId=from.path("id").asLong();
        if(supportService.isAdmin(telegramId)) {
            if(message.has("reply_to_message")) { supportService.reply(updateId,message);return; }
            else if(!text.startsWith("/")) return;
        }
        boolean start=text.startsWith("/start");
        var registration=start
                ?userService.registerStart(telegramId,nullable(from,"username"),nullable(from,"first_name"),parameter)
                :userService.registerOrUpdateWithStatus(telegramId,nullable(from,"username"),nullable(from,"first_name"),null);
        User user=registration.user();
        if(user.banned()) { client.send(chatId,"Доступ к боту заблокирован.",null);return; }
        if(start) {
            Subscription current=subscriptionService.find(telegramId);
            if(!user.trialUsed()&&current==null) {activateTrialAndConnect(chatId,user);return;}
            if(current!=null&&current.active(clock.instant())&&current.syncStatus()!=com.abcvpn.enumeration.SyncStatus.SYNCED) {
                recoverPendingSubscription(chatId,user,current);return;
            }
            showMain(chatId,null,user);return;
        }
        if(text.startsWith("/invite")) showInvite(chatId,null,user); else if(text.startsWith("/mysub")) showSubscription(chatId,null,user);
        else if(text.startsWith("/help")) showSupport(chatId,null);
        else if(text.startsWith("/")) showMain(chatId,null,user);
        else if(isSupportMessage(message)) supportService.forward(updateId,message,user);
        else showMain(chatId,null,user);
    }
    private void handleCallback(JsonNode callback) {
        String id=callback.path("id").asText();String data=callback.path("data").asText();JsonNode from=callback.path("from");
        long telegramId=from.path("id").asLong();User user=userService.registerOrUpdate(telegramId,nullable(from,"username"),nullable(from,"first_name"),null);
        long chatId=callback.path("message").path("chat").path("id").asLong();int messageId=callback.path("message").path("message_id").asInt();
        try {
            if(user.banned()) { client.answer(id,"Доступ заблокирован");return; }
            if("qr_delete".equals(data)) {client.delete(chatId,messageId);client.answer(id,null);return;}
            if("main".equals(data)) showMain(chatId,messageId,user);
            else if("tariffs".equals(data)) showTariffs(chatId,messageId);
            else if("trial".equals(data)) {
                var subscription=trialService.activate(telegramId);
                client.notifyAdmin("🎁 <b>Активирован trial</b>\n\nID: <code>"+telegramId+"</code>\nUsername: "+
                        (user.username()==null?"—":"@"+escape(user.username()))+"\nДействует до: "+subscription.expiresAt());
                showMain(chatId,messageId,userService.requireActive(telegramId));
            }
            else if(data.startsWith("tariff:")) showPayment(chatId,messageId,telegramId,Long.parseLong(data.substring(7)));
            else if(data.startsWith("pay:")) showPayment(chatId,messageId,telegramId,Long.parseLong(data.substring(4)));
            else if("connect".equals(data)) showConnection(chatId,messageId,telegramId);
            else if(data.startsWith("device:")) showDevice(chatId,messageId,telegramId,data.substring(7));
            else if("key".equals(data)) showKey(chatId,messageId,telegramId);
            else if("qr".equals(data)) client.sendQr(chatId,subscriptionService.requireConnectionUrl(telegramId));
            else if("devices".equals(data)) showDevices(chatId,messageId,telegramId);
            else if(data.startsWith("device_block:")) {deviceService.block(telegramId,Long.parseLong(data.substring(13)));showDevices(chatId,messageId,telegramId);}
            else if(data.startsWith("device_activate:")) activateDevice(chatId,messageId,telegramId,Long.parseLong(data.substring(16)));
            else if(data.startsWith("device_replace:")) replaceDevice(chatId,messageId,telegramId,data.substring(15));
            else if("devices_reset".equals(data)) showResetDevices(chatId,messageId);
            else if("devices_reset_confirm".equals(data)) {deviceService.reset(telegramId);showDevices(chatId,messageId,telegramId);}
            else if("invite".equals(data)) showInvite(chatId,messageId,user);
            else if(data.startsWith("wallet_buy:")) showWalletPayment(chatId,messageId,telegramId,data.substring(11));
            else if(data.startsWith("wallet_history:")) showWalletHistory(chatId,messageId,telegramId,Integer.parseInt(data.substring(15)));
            else if("documents".equals(data)) showDocuments(chatId,messageId);
            else if("support".equals(data)) showSupport(chatId,messageId);
            else if("return_bonus".equals(data)) {activateReturnBonus(chatId,messageId,telegramId);}
            client.answer(id,null);
        } catch(RuntimeException exception) {
            log.warn("[TELEGRAM] Callback {} failed",data,exception);
            try { client.answer(id,exception.getMessage()); }
            catch(RuntimeException answerException) { log.warn("[TELEGRAM] Callback answer failed"); }
        }
    }

    private void showMain(long chatId,Integer messageId,User user) {
        Subscription subscription=subscriptionService.find(user.telegramId());boolean active=subscription!=null&&subscription.active(clock.instant());
        String text="🛡️ <b>abc-vpn</b>\nТвой интернет. Твои правила.\n\n👋 Привет, "+escape(user.firstName()==null?"друг":user.firstName())+"!\n\n";
        List<TelegramClient.Row> rows=new ArrayList<>();
        if(active) {
            String remaining=subscription.expiresAt()==null?"без срока":Math.max(0,Duration.between(clock.instant(),subscription.expiresAt()).toDays())+" дн.";
            text+="🟢 VPN активен\n⏳ Осталось: <b>"+remaining+"</b>";
            rows.add(TelegramClient.row(TelegramClient.callback("🚀 Подключить VPN","connect")));
            rows.add(TelegramClient.row(TelegramClient.callback("💎 Продлить VPN","tariffs"),TelegramClient.callback("📱 Мои устройства","devices")));
        } else {
            text+="⚪ VPN не активен\n\nВыберите вариант подключения:";
            if(!user.trialUsed()) {text+="\n\n🎁 Вам доступен бесплатный пробный период.";rows.add(TelegramClient.row(TelegramClient.callback("🎁 Активировать бесплатно","trial")));}
            rows.add(TelegramClient.row(TelegramClient.callback("💳 Купить подписку","tariffs")));
        }
        rows.add(TelegramClient.row(TelegramClient.callback("💰 Реферальная программа","invite")));
        rows.add(TelegramClient.row(TelegramClient.callback("💬 Поддержка","support"),TelegramClient.callback("📄 Документы","documents")));
        output(chatId,messageId,text,new TelegramClient.Keyboard(rows));
    }
    private void showTariffs(long chatId,int messageId) {
        List<TelegramClient.Row> rows=new ArrayList<>();
        for(Tariff tariff:tariffService.enabled()) rows.add(TelegramClient.row(TelegramClient.callback(tariff.name()+" — "+tariff.priceRub().setScale(0,RoundingMode.UNNECESSARY)+" ₽","tariff:"+tariff.id())));
        rows.add(back());output(chatId,messageId,"💳 Выберите тариф:",new TelegramClient.Keyboard(rows));
    }
    private void showPayment(long chatId,int messageId,long telegramId,long tariffId) {
        PaymentLink link=paymentService.create(telegramId,tariffId);
        output(chatId,messageId,"Платёж подготовлен. После оплаты доступ будет продлён автоматически.",
                TelegramClient.keyboard(TelegramClient.row(TelegramClient.url("💳 Оплатить",link.url())),back()));
    }
    private void showConnection(long chatId,Integer messageId,long telegramId) {
        Subscription subscription=subscriptionService.requireActive(telegramId);String url=subscriptionService.requireConnectionUrl(telegramId);
        analytics.record(telegramId,AnalyticsEventService.CONNECTION_SCREEN_OPENED);
        analytics.record(telegramId,AnalyticsEventService.SUBSCRIPTION_LINK_REQUESTED);
        String text="⚡ <b>Подключение abc-vpn</b>\n\n🟢 VPN активен\n⏳ Осталось: <b>"+remaining(subscription)+"</b>\n\n🔑 Ваша ссылка подписки:\n<code>"+escape(url)+"</code>\n\nСкопируйте ссылку или подключитесь автоматически.";
        output(chatId,messageId,text,TelegramClient.keyboard(
                TelegramClient.row(TelegramClient.url("🚀 Подключиться в 1 клик",oneClickUrl(properties.apiBaseUrl(),telegramId,properties.adminSessionSecret(),clock.instant()))),
                rowDevice("🤖 Android","android","🍎 iPhone / iPad","ios"),rowDevice("🖥️ Windows","windows","💻 macOS","macos"),
                TelegramClient.row(TelegramClient.callback("📷 QR-код","qr"),TelegramClient.callback("🐧 Linux","device:linux")),back()));
    }
    private void showDevice(long chatId,int messageId,long telegramId,String platform) {
        analytics.record(telegramId,AnalyticsEventService.PLATFORM_SELECTED);
        String url=subscriptionService.requireConnectionUrl(telegramId);
        String[] guide=guide(platform);String text="<b>"+guide[0]+"</b>\n\n"+guide[1]+"\n\nЕсли автоматическая настройка не сработала:\n1. Скопируйте ключ ниже.\n2. Откройте Happ и нажмите <b>Вставить/Из буфера</b>.\n3. Выберите локацию и подключитесь.\n\n<code>"+escape(url)+"</code>";
        List<TelegramClient.Row> rows=new ArrayList<>();
        addDownloadButtons(rows,platform);
        if(!"router".equals(platform)) rows.add(TelegramClient.row(TelegramClient.url("🚀 Подключиться в 1 клик",oneClickUrl(properties.apiBaseUrl(),telegramId,properties.adminSessionSecret(),clock.instant()))));
        rows.add(TelegramClient.row(TelegramClient.callback("QR-код","qr")));
        rows.add(TelegramClient.row(TelegramClient.callback("Другие устройства","connect")));
        rows.add(TelegramClient.row(TelegramClient.callback("⬅ Назад в меню","main")));
        output(chatId,messageId,text,new TelegramClient.Keyboard(rows));
    }
    private String[] guide(String platform) {
        return switch(platform) {
            case "android"->new String[]{"Настройка VPN на Android","1. Скачайте Happ из Google Play или APK-файлом.\n2. Нажмите кнопку ниже, чтобы подключиться в 1 клик."};
            case "ios"->new String[]{"Настройка VPN на iPhone / iPad","1. Скачайте Happ для России или других регионов.\n2. Нажмите кнопку ниже, чтобы подключиться в 1 клик."};
            case "windows"->new String[]{"Настройка VPN на Windows","1. Скачайте и установите Happ.\n2. Нажмите кнопку ниже, чтобы подключиться в 1 клик."};
            case "macos"->new String[]{"Настройка VPN на macOS","1. Скачайте Happ для России или других регионов.\n2. Нажмите кнопку ниже, чтобы подключиться в 1 клик."};
            case "androidtv"->new String[]{"Настройка VPN на Android TV","1. Установите Happ из Google Play или APK-файлом.\n2. Импортируйте ключ через QR-код или буфер обмена."};
            case "appletv"->new String[]{"Настройка VPN на Apple TV","1. Установите Happ из App Store на TV.\n2. Откройте Happ на TV и покажите QR-код.\n3. На телефоне в Happ нажмите QR-код и отсканируйте экран TV.\n4. Выберите ключ и отправьте его на Apple TV."};
            case "linux"->new String[]{"Настройка VPN на Linux","1. Скачайте и установите Happ.\n2. Нажмите кнопку ниже, чтобы подключиться в 1 клик."};
            default->new String[]{"Настройка VPN на роутере","Импортируйте ключ подписки в совместимый VPN-клиент вашего роутера."};
        };
    }
    private void addDownloadButtons(List<TelegramClient.Row> rows,String platform) {
        switch(platform) {
            case "android","androidtv" -> rows.add(TelegramClient.row(
                    TelegramClient.url("Google Play","https://play.google.com/store/apps/details?id=com.happproxy"),
                    TelegramClient.url("APK-файл","https://github.com/Happ-proxy/happ-android/releases/latest/download/Happ.apk")));
            case "ios","macos" -> rows.add(TelegramClient.row(
                    TelegramClient.url("App Store Россия","https://apps.apple.com/ru/app/happ-proxy-utility/id6783623643"),
                    TelegramClient.url("Другие регионы","https://apps.apple.com/us/app/happ-proxy-utility/id6504287215")));
            case "windows" -> rows.add(TelegramClient.row(TelegramClient.url("Скачать Happ для Windows","https://github.com/Happ-proxy/happ-desktop/releases/latest/download/setup-Happ.x64.exe")));
            case "linux" -> rows.add(TelegramClient.row(TelegramClient.url("Скачать Happ для Linux","https://github.com/Happ-proxy/happ-desktop/releases/latest/download/Happ.linux.x64.deb")));
            case "appletv" -> rows.add(TelegramClient.row(TelegramClient.url("Скачать Happ для Apple TV","https://apps.apple.com/us/app/happ-proxy-utility-for-tv/id6748297274")));
            default -> { }
        }
    }
    static String oneClickUrl(String apiBaseUrl,long telegramId,String secret,Instant now) {
        long expires=now.plus(Duration.ofDays(30)).getEpochSecond();
        return apiBaseUrl+"/api/v1/redirect_dl?user="+telegramId+"&expires="+expires+"&signature="+OneClickSignature.create(telegramId,expires,secret);
    }
    private void showKey(long chatId,int messageId,long telegramId) {String url=subscriptionService.requireConnectionUrl(telegramId);output(chatId,messageId,"📋 <b>Ключ подключения</b>\n\n<code>"+escape(url)+"</code>",TelegramClient.keyboard(TelegramClient.row(TelegramClient.callback("QR","qr")),TelegramClient.row(TelegramClient.callback("⬅ Назад","connect"))));}
    private void showDevices(long chatId,int messageId,long telegramId) {
        subscriptionService.requireActive(telegramId);DeviceService.DeviceOverview overview=deviceService.overview(telegramId);
        StringBuilder text=new StringBuilder("📱 <b>Мои устройства</b>\n\nАктивно: <b>").append(overview.activeCount()).append(" из ").append(overview.deviceLimit()).append("</b>");
        List<TelegramClient.Row> rows=new ArrayList<>();
        for(var device:overview.devices()) {
            text.append("\n\n").append(escape(device.deviceModel()==null?"Неизвестное устройство":device.deviceModel()))
                    .append("\n").append(escape(device.deviceOs()==null?"OS неизвестна":device.deviceOs()))
                    .append(device.osVersion()==null?"":" "+escape(device.osVersion())).append(" — <b>").append(device.status()).append("</b>");
            String action=device.status()==com.abcvpn.enumeration.DeviceStatus.ACTIVE?"device_block:":"device_activate:";
            String label=device.status()==com.abcvpn.enumeration.DeviceStatus.ACTIVE?"🚫 Отключить ":"✅ Сделать активным ";
            rows.add(TelegramClient.row(TelegramClient.callback(label+shortName(device.deviceModel()),action+device.id())));
        }
        rows.add(TelegramClient.row(TelegramClient.callback("➕ Подключить устройство","connect")));
        if(!overview.devices().isEmpty()) rows.add(TelegramClient.row(TelegramClient.callback("♻️ Сбросить все устройства","devices_reset")));
        rows.add(back());output(chatId,messageId,text.toString(),new TelegramClient.Keyboard(rows));
    }
    private void activateDevice(long chatId,int messageId,long telegramId,long deviceId) {
        if(deviceService.activate(telegramId,deviceId)) {showDevices(chatId,messageId,telegramId);return;}
        var overview=deviceService.overview(telegramId);List<TelegramClient.Row> rows=new ArrayList<>();
        for(var device:overview.devices()) if(device.status()==com.abcvpn.enumeration.DeviceStatus.ACTIVE)
            rows.add(TelegramClient.row(TelegramClient.callback("🚫 "+shortName(device.deviceModel()),"device_replace:"+deviceId+":"+device.id())));
        rows.add(TelegramClient.row(TelegramClient.callback("⬅ Назад","devices")));
        output(chatId,messageId,"Лимит активных устройств занят.\n\n<b>Выберите устройство, которое нужно отключить:</b>",new TelegramClient.Keyboard(rows));
    }
    private void replaceDevice(long chatId,int messageId,long telegramId,String ids) {
        String[] parts=ids.split(":",2);deviceService.replace(telegramId,Long.parseLong(parts[0]),Long.parseLong(parts[1]));
        showDevices(chatId,messageId,telegramId);
    }
    private void showResetDevices(long chatId,int messageId) {
        output(chatId,messageId,"♻️ <b>Сбросить все устройства?</b>\n\nВсе устройства будут удалены. Лимит тарифа не изменится.",
                TelegramClient.keyboard(TelegramClient.row(TelegramClient.callback("Да, сбросить","devices_reset_confirm")),TelegramClient.row(TelegramClient.callback("Отмена","devices"))));
    }
    private String shortName(String model) {String value=model==null||model.isBlank()?"устройство":model;return value.length()>24?value.substring(0,24):value;}
    private void showSubscription(long chatId,Integer messageId,User user) {showMain(chatId,messageId,user);}
    private void showInvite(long chatId,Integer messageId,User user) {
        ReferralService.ReferralStats stats=referralService.stats(user.telegramId());
        String link="https://t.me/"+properties.telegram().botUsername()+"?start=ref_"+user.referralCode();
        String text="💰 <b>Реферальная программа</b>\n\nПриглашайте друзей и получайте <b>"+ReferralService.REWARD_PERCENT+"% с каждой их оплаты!</b>\n"+
                "Минимальный вывод: <b>"+money(ReferralService.MIN_WITHDRAWAL_KOPECKS)+" ₽</b> — <a href=\"https://t.me/abc_vpn_support\">через поддержку</a>\n\n"+
                "— Реферальный баланс: <b>"+money(stats.balanceKopecks())+" ₽</b>\n"+
                "— Всего приглашено: <b>"+stats.invited()+" чел.</b>\n"+
                "— Активных с подпиской: <b>"+stats.activePaid()+" чел.</b> ⚡\n\n"+
                "🔗 <b>Ваша ссылка:</b>\n<code>"+escape(link)+"</code>\n\n"+
                "<i>Вы можете использовать реферальный баланс для оплаты своей подписки кнопками ниже 👇</i>";
        String key=UUID.randomUUID().toString().replace("-","").substring(0,10);
        List<Tariff> tariffs=tariffService.referralWalletTariffs();
        String share="https://t.me/share/url?url="+encode(link)+"&text="+encode("abc-vpn — быстрый VPN, первые 3 дня бесплатно.\nПопробовать:");
        output(chatId,messageId,text,TelegramClient.keyboard(
                TelegramClient.row(walletButton(tariffs.get(0),key),walletButton(tariffs.get(1),key)),
                TelegramClient.row(walletButton(tariffs.get(2),key)),
                TelegramClient.row(TelegramClient.url("📤 Поделиться ссылкой",share)),
                TelegramClient.row(TelegramClient.callback("📜 История","wallet_history:0")),back()));
    }
    private TelegramClient.Button walletButton(Tariff tariff,String key) {
        return TelegramClient.callback(tariff.name()+" ("+money(WalletService.kopecks(tariff.priceRub()))+" ₽)",
                "wallet_buy:"+tariff.id()+":"+key);
    }
    private void showWalletPayment(long chatId,int messageId,long telegramId,String value) {
        String[] parts=value.split(":",2);long tariffId=Long.parseLong(parts[0]);String key=parts[1];
        Tariff tariff=tariffService.requireWalletPurchasable(tariffId);
        PaymentService.PurchaseResult result=paymentService.createWithBalance(telegramId,tariffId,key);
        if(result.completed()) {
            output(chatId,messageId,"✅ <b>Подписка оплачена с реферального баланса</b>\n\nСрок подписки увеличен на <b>"+
                    tariff.durationDays()+" дн.</b>.",TelegramClient.keyboard(TelegramClient.row(TelegramClient.callback("💰 К реферальной программе","invite")),back()));
            return;
        }
        String text="💳 <b>Оплата подписки</b>\n\nСтоимость: <b>"+money(WalletService.kopecks(tariff.priceRub()))+" ₽</b>\n"+
                "С баланса: <b>"+money(WalletService.kopecks(result.balanceApplied()))+" ₽</b>\n"+
                "К доплате: <b>"+money(WalletService.kopecks(result.externalAmount()))+" ₽</b>";
        output(chatId,messageId,text,TelegramClient.keyboard(
                TelegramClient.row(TelegramClient.url("💳 Доплатить "+money(WalletService.kopecks(result.externalAmount()))+" ₽",result.paymentLink().url())),
                TelegramClient.row(TelegramClient.callback("⬅️ Назад","invite"))));
    }
    private void showWalletHistory(long chatId,int messageId,long telegramId,int page) {
        var operations=walletService.history(telegramId,page,6);StringBuilder text=new StringBuilder("📜 <b>История реферального баланса</b>\n");
        DateTimeFormatter date=DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Moscow"));
        for(var operation:operations) text.append("\n").append("CREDIT".equals(operation.direction())?"+":"−")
                .append(money(operation.amountKopecks())).append(" ₽ — ").append(operationName(operation.type()))
                .append("\n").append(date.format(operation.createdAt())).append("\n");
        if(operations.isEmpty()) text.append("\nОпераций пока нет.\n");
        text.append("\n<b>Текущий баланс: ").append(money(walletService.account(telegramId).availableKopecks())).append(" ₽</b>");
        List<TelegramClient.Row> rows=new ArrayList<>();
        if(page>0) rows.add(TelegramClient.row(TelegramClient.callback("← Новее","wallet_history:"+(page-1))));
        if(operations.size()==6) rows.add(TelegramClient.row(TelegramClient.callback("Старее →","wallet_history:"+(page+1))));
        rows.add(TelegramClient.row(TelegramClient.callback("⬅️ Назад","invite")));
        output(chatId,messageId,text.toString(),new TelegramClient.Keyboard(rows));
    }
    private String operationName(com.abcvpn.enumeration.WalletTransactionType type) {
        return switch(type) {case REFERRAL_REWARD->"оплата реферала";case SUBSCRIPTION_PAYMENT,SUBSCRIPTION_PAYMENT_RESERVE->"оплата подписки";
            case WITHDRAWAL,WITHDRAWAL_HOLD->"вывод средств";case ADMIN_ADJUSTMENT->"корректировка";default->"операция баланса";};
    }
    private String money(long kopecks) {return WalletService.rubles(kopecks).stripTrailingZeros().toPlainString();}
    private String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private void showDocuments(long chatId,Integer messageId) {output(chatId,messageId,"📄 <b>Документы</b>",TelegramClient.keyboard(TelegramClient.row(TelegramClient.url("Пользовательское соглашение",properties.termsUrl())),TelegramClient.row(TelegramClient.url("Политика конфиденциальности",properties.privacyPolicyUrl())),TelegramClient.row(TelegramClient.callback("Тарифы","tariffs")),back()));}
    private void showSupport(long chatId,Integer messageId) {output(chatId,messageId,"💬 <b>Поддержка abc-vpn</b>\n\nВозник вопрос или проблема с подключением? Просто напишите сообщение прямо в этот бот — мы ответим здесь же.\n\nИли свяжитесь с поддержкой напрямую: "+escape(properties.supportUsername()),TelegramClient.keyboard(TelegramClient.row(TelegramClient.url("💬 Открыть чат "+properties.supportUsername(),"https://t.me/"+properties.supportUsername().replace("@",""))),back()));}
    private void activateTrialAndConnect(long chatId,User user) {
        try {
            Subscription subscription=trialService.activate(user.telegramId());
            analytics.record(user.telegramId(),AnalyticsEventService.TRIAL_AUTO_ACTIVATED);
            client.notifyAdmin("🎁 <b>Автоматически активирован trial</b>\n\nID: <code>"+user.telegramId()+"</code>\nUsername: "+(user.username()==null?"—":"@"+escape(user.username()))+"\nДействует до: "+subscription.expiresAt());
            vpnAccessService.sync(subscription);
            showConnection(chatId,null,user.telegramId());
        } catch(RuntimeException exception) {
            log.warn("[TELEGRAM] Automatic trial activation failed for {}",user.telegramId());
            User refreshed=userService.requireActive(user.telegramId());showMain(chatId,null,refreshed);
        }
    }
    private void recoverPendingSubscription(long chatId,User user,Subscription subscription) {
        try {
            vpnAccessService.sync(subscription);
            Subscription refreshed=subscriptionService.requireActive(user.telegramId());
            if("TRIAL".equals(tariffService.require(refreshed.tariffId()).code())) showConnection(chatId,null,user.telegramId());
            else showMain(chatId,null,user);
        } catch(RuntimeException exception) {
            log.warn("[TELEGRAM] Pending subscription recovery failed for {}",user.telegramId());
            showMain(chatId,null,user);
        }
    }
    private void activateReturnBonus(long chatId,Integer messageId,long telegramId) {
        Subscription subscription=returnBonusService.activate(telegramId);
        try {vpnAccessService.sync(subscription);showConnection(chatId,messageId,telegramId);}
        catch(RuntimeException exception) {
            log.warn("[REMNAWAVE] Return bonus sync deferred for user {}",telegramId);
            output(chatId,messageId,"🎁 <b>2 дня активированы</b>\n\nVPN-доступ подготавливается. Попробуйте подключиться через минуту.",TelegramClient.keyboard(back()));
        }
    }
    private String remaining(Subscription subscription) {
        if(subscription.expiresAt()==null) return "без срока";
        long seconds=Math.max(0,Duration.between(clock.instant(),subscription.expiresAt()).getSeconds());
        return Math.max(1,(seconds+86399)/86400)+" дн.";
    }
    private TelegramClient.Row rowDevice(String a,String ad,String b,String bd){return TelegramClient.row(TelegramClient.callback(a,"device:"+ad),TelegramClient.callback(b,"device:"+bd));}
    private TelegramClient.Row back(){return TelegramClient.row(TelegramClient.callback("⬅ Назад","main"));}
    private void output(long chatId,Integer messageId,String text,TelegramClient.Keyboard keyboard){if(messageId==null)client.send(chatId,text,keyboard);else client.edit(chatId,messageId,text,keyboard);}
    private String nullable(JsonNode node,String name){return node.hasNonNull(name)?node.get(name).asText():null;}
    private boolean isSupportMessage(JsonNode message) {
        return message.hasNonNull("text")||message.has("photo")||message.has("video")||message.has("document")||
                message.has("voice")||message.has("video_note");
    }
    private String escape(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}
