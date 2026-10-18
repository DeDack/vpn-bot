package com.abcvpn.controller;

import java.net.URI;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.service.AnalyticsEventService;
import com.abcvpn.service.OneClickSignature;
import com.abcvpn.service.SubscriptionService;

@RestController
public class OneClickRedirectController {
    private static final URI TRUSTED_LEGACY_SUBSCRIPTION_BASE=URI.create("https://sub.abc-vpn.example.com");
    private final SubscriptionService subscriptionService;private final AnalyticsEventService analytics;
    private final String signingSecret;private final URI trustedSubscriptionBase;private final String botUsername;private final Clock clock;
    public OneClickRedirectController(SubscriptionService subscriptionService,AnalyticsEventService analytics,
                                      AbcVpnProperties properties,Clock clock) {
        this.subscriptionService=subscriptionService;this.analytics=analytics;this.signingSecret=properties.adminSessionSecret();
        this.trustedSubscriptionBase=TRUSTED_LEGACY_SUBSCRIPTION_BASE;
        this.botUsername=properties.telegram().botUsername().replace("@","");this.clock=clock;
    }
    @GetMapping("/api/v1/redirect_dl")
    public ResponseEntity<Void> redirect(@RequestParam(required=false) Long user,
                                         @RequestParam(required=false) Long expires,
                                         @RequestParam(required=false) String signature,
                                         @RequestParam(required=false) String url) {
        if(url!=null) return legacyRedirect(user,expires,signature,url);
        if(user==null||expires==null||signature==null||!OneClickSignature.valid(user,expires,signature,signingSecret))
            throw new FlowException(ErrorCode.ACCESS_DENIED);
        if(expires<clock.instant().getEpochSecond())
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("https://t.me/"+botUsername+"?start=connect")).build();
        String targetUrl="happ://add/"+subscriptionService.requireConnectionUrl(user);
        ResponseEntity<Void> response=redirectToHapp(targetUrl);
        analytics.record(user,AnalyticsEventService.ONE_CLICK_REDIRECT_OPENED);
        return response;
    }
    private ResponseEntity<Void> legacyRedirect(Long user,Long expires,String signature,String url) {
        if(user!=null||expires!=null||signature!=null) throw new FlowException(ErrorCode.INVALID_REQUEST);
        URI deepLink=parse(url);URI subscription=parseLegacySubscription(deepLink);
        subscriptionService.requireLegacyConnectionUrl(subscription.toString());
        return ResponseEntity.status(HttpStatus.FOUND).location(deepLink).build();
    }
    private URI parseLegacySubscription(URI deepLink) {
        if(!"happ".equalsIgnoreCase(deepLink.getScheme())||!"add".equalsIgnoreCase(deepLink.getHost())||deepLink.getPort()!=-1||
                deepLink.getUserInfo()!=null||deepLink.getQuery()!=null||deepLink.getFragment()!=null) throw new FlowException(ErrorCode.INVALID_REQUEST);
        String path=deepLink.getRawPath();
        if(path==null||path.length()<2||path.indexOf('%')>=0) throw new FlowException(ErrorCode.INVALID_REQUEST);
        URI subscription=parse(path.substring(1));
        if(!sameOrigin(subscription,trustedSubscriptionBase)||subscription.getUserInfo()!=null||subscription.getFragment()!=null||
                subscription.getQuery()!=null||!trustedPath(subscription.getPath(),trustedSubscriptionBase.getPath()))
            throw new FlowException(ErrorCode.ACCESS_DENIED);
        return subscription;
    }
    private boolean sameOrigin(URI value,URI expected) {
        return expected.getScheme()!=null&&expected.getScheme().equalsIgnoreCase(value.getScheme())&&expected.getHost()!=null&&
                expected.getHost().equalsIgnoreCase(value.getHost())&&effectivePort(expected)==effectivePort(value);
    }
    private int effectivePort(URI value) {
        if(value.getPort()!=-1) return value.getPort();
        return "https".equalsIgnoreCase(value.getScheme())?443:"http".equalsIgnoreCase(value.getScheme())?80:-1;
    }
    private boolean trustedPath(String value,String base) {
        String prefix=base==null||base.isBlank()?"/":base;
        if(!prefix.endsWith("/")) prefix+="/";
        return value!=null&&value.startsWith(prefix)&&value.length()>prefix.length();
    }
    private ResponseEntity<Void> redirectToHapp(String url) {
        URI target=parse(url);
        if(!"happ".equalsIgnoreCase(target.getScheme())) throw new FlowException(ErrorCode.INVALID_REQUEST);
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }
    private URI parse(String url) {
        URI target;
        try { target=URI.create(url); }
        catch(IllegalArgumentException exception) { throw new FlowException(ErrorCode.INVALID_REQUEST); }
        return target;
    }
}
