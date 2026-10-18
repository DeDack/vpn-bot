package com.abcvpn.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.entity.Tariff;
import com.abcvpn.repository.TariffRepository;

class LandingControllerTest {
    @Test
    void rendersEnabledTariffsAndTrackedBotLink() {
        TariffRepository tariffs=mock(TariffRepository.class);
        AbcVpnProperties properties=mock(AbcVpnProperties.class);
        when(properties.telegram()).thenReturn(new AbcVpnProperties.Telegram("",1,"@abc_vpn_bot",true));
        when(properties.supportUsername()).thenReturn("@abc_vpn_support");
        when(properties.privacyPolicyUrl()).thenReturn("https://abc-vpn.example.com/privacy");
        when(properties.termsUrl()).thenReturn("https://abc-vpn.example.com/terms");
        when(tariffs.findEnabled()).thenReturn(List.of(new Tariff(1,"BASIC","1 месяц",30,
                BigDecimal.valueOf(150),10,null,UUID.randomUUID(),true,10),new Tariff(5,"BASIC_12M","12 месяцев · скидка 50%",365,
                BigDecimal.valueOf(900),10,null,UUID.randomUUID(),true,40)));
        ConcurrentModel model=new ConcurrentModel();

        String view=new LandingController(tariffs,properties).landing(model);

        assertThat(view).isEqualTo("landing");
        assertThat(model.getAttribute("botLink")).isEqualTo("https://t.me/abc_vpn_bot?start=ad_landing");
        assertThat((List<?>)model.getAttribute("tariffs")).hasSize(2);

        ClassLoaderTemplateResolver resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");
        SpringTemplateEngine engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        Context context=new Context();context.setVariables(model.asMap());
        assertThat(engine.process("landing",context)).contains("abc-vpn — быстрый VPN",">150</strong>",">75</strong>","₽ / мес.");

        ConcurrentModel legalModel=new ConcurrentModel();
        LandingController controller=new LandingController(tariffs,properties);
        assertThat(controller.privacy(legalModel)).isEqualTo("privacy");
        Context legalContext=new Context();legalContext.setVariables(legalModel.asMap());
        assertThat(engine.process("privacy",legalContext)).contains("Политика конфиденциальности","@abc_vpn_support");
        assertThat(controller.terms(legalModel)).isEqualTo("terms");
        legalContext.setVariables(legalModel.asMap());
        assertThat(engine.process("terms",legalContext)).contains("Пользовательское соглашение","@abc_vpn_support");
    }
}
