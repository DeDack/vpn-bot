package com.abcvpn.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.entity.Tariff;
import com.abcvpn.repository.TariffRepository;

@Controller
public class LandingController {
    private static final String CAMPAIGN_CODE = "landing";
    private final TariffRepository tariffRepository;
    private final AbcVpnProperties properties;

    public LandingController(TariffRepository tariffRepository,AbcVpnProperties properties) {
        this.tariffRepository=tariffRepository;this.properties=properties;
    }

    @GetMapping("/")
    public String landing(Model model) {
        String botUsername=properties.telegram().botUsername().replace("@","");
        model.addAttribute("botLink","https://t.me/"+botUsername+"?start=ad_"+CAMPAIGN_CODE);
        model.addAttribute("supportUsername",properties.supportUsername());
        model.addAttribute("privacyUrl",properties.privacyPolicyUrl());
        model.addAttribute("termsUrl",properties.termsUrl());
        model.addAttribute("tariffs",tariffRepository.findEnabled().stream().map(this::card).toList());
        return "landing";
    }

    @GetMapping("/privacy")
    public String privacy(Model model) { return legal(model,"Политика конфиденциальности"); }

    @GetMapping("/terms")
    public String terms(Model model) { return legal(model,"Пользовательское соглашение"); }

    private String legal(Model model,String title) {
        model.addAttribute("title",title);
        model.addAttribute("supportUsername",properties.supportUsername());
        return "Политика конфиденциальности".equals(title)?"privacy":"terms";
    }

    private TariffCard card(Tariff tariff) {
        int months=Math.max(1,(tariff.durationDays()+15)/30);
        BigDecimal monthly=tariff.priceRub().divide(BigDecimal.valueOf(months),0,RoundingMode.HALF_UP);
        return new TariffCard(tariff.code(),tariff.name(),tariff.priceRub(),monthly,
                tariff.durationDays(),tariff.deviceLimit(),"BASIC_6M".equals(tariff.code()));
    }

    public record TariffCard(String code,String name,BigDecimal price,BigDecimal monthly,
                             int durationDays,int deviceLimit,boolean popular) {}
}
