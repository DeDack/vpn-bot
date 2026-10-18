package com.abcvpn.admin;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.abcvpn.config.AbcVpnProperties;

@Controller
public class AdminController {
    private final AdminService adminService;private final AbcVpnProperties properties;
    public AdminController(AdminService adminService,AbcVpnProperties properties) {
        this.adminService=adminService;this.properties=properties;
    }
    @GetMapping("/admin")
    public String dashboard(@RequestParam(defaultValue="") String q,Model model) {
        AdminService.Dashboard dashboard=adminService.dashboard(q);
        model.addAttribute("users",dashboard.users());model.addAttribute("subscriptions",dashboard.subscriptions());model.addAttribute("tariffs",dashboard.tariffs());
        model.addAttribute("payments",dashboard.payments());model.addAttribute("campaigns",dashboard.campaigns());model.addAttribute("campaignStats",dashboard.campaignStats());
        model.addAttribute("syncErrors",dashboard.syncErrors());model.addAttribute("q",q);
        model.addAttribute("devices",dashboard.devices());
        model.addAttribute("referrals",dashboard.referrals());
        model.addAttribute("analytics",adminService.analytics());
        model.addAttribute("botUsername",properties.telegram().botUsername());return "admin/index";
    }
    @GetMapping("/admin/analytics")
    public String analytics(@RequestParam(defaultValue="all") String period,Model model) {
        model.addAttribute("analytics",adminService.analytics(period));
        model.addAttribute("botUsername",properties.telegram().botUsername());
        return "admin/analytics";
    }
    @PostMapping("/admin/users/{id}/ban") public String ban(@PathVariable long id){adminService.ban(id);return redirect();}
    @PostMapping("/admin/users/{id}/unban") public String unban(@PathVariable long id){adminService.unban(id);return redirect();}
    @PostMapping("/admin/users/{id}/revoke") public String revoke(@PathVariable long id){adminService.revoke(id);return redirect();}
    @PostMapping("/admin/users/{id}/retry") public String retry(@PathVariable long id){adminService.retry(id);return redirect();}
    @PostMapping("/admin/users/{id}/extend") public String extend(@PathVariable long id,@RequestParam int days){adminService.extend(id,days);return redirect();}
    @PostMapping("/admin/users/{id}/grant") public String grant(@PathVariable long id,@RequestParam long tariffId,@RequestParam(required=false) Integer days){adminService.grant(id,tariffId,days);return redirect();}
    @PostMapping("/admin/users/{id}/message") public String message(@PathVariable long id,@RequestParam String text){adminService.sendMessage(id,text);return redirect();}
    @PostMapping("/admin/users/{id}/delete") public String deleteUser(@PathVariable long id){adminService.deleteUser(id);return redirect();}
    @PostMapping("/admin/users/{id}/devices/{deviceId}/activate") public String activateDevice(@PathVariable long id,@PathVariable long deviceId){adminService.activateDevice(id,deviceId);return redirectDevices();}
    @PostMapping("/admin/users/{id}/devices/{deviceId}/block") public String blockDevice(@PathVariable long id,@PathVariable long deviceId){adminService.blockDevice(id,deviceId);return redirectDevices();}
    @PostMapping("/admin/users/{id}/devices/{deviceId}/replace") public String replaceDevice(@PathVariable long id,@PathVariable long deviceId,@RequestParam long activeDeviceId){adminService.replaceDevice(id,deviceId,activeDeviceId);return redirectDevices();}
    @PostMapping("/admin/users/{id}/devices/reset") public String resetDevices(@PathVariable long id){adminService.resetDevices(id);return redirectDevices();}
    @PostMapping("/admin/tariffs/save")
    public String tariff(@RequestParam(required=false)Long id,@RequestParam String code,@RequestParam String name,
                         @RequestParam int durationDays,@RequestParam BigDecimal priceRub,@RequestParam int deviceLimit,
                         @RequestParam(required=false)Long trafficLimit,@RequestParam UUID squadId,
                         @RequestParam(defaultValue="false")boolean enabled,@RequestParam int sortOrder) {
        adminService.saveTariff(id,code,name,durationDays,priceRub,deviceLimit,trafficLimit,squadId,enabled,sortOrder);return redirect();
    }
    @PostMapping("/admin/tariffs/{id}/enabled") public String tariffEnabled(@PathVariable long id,@RequestParam boolean enabled){adminService.setTariffEnabled(id,enabled);return redirect();}
    @PostMapping("/admin/campaigns/create") public String campaignCreate(@RequestParam String code,@RequestParam String name,@RequestParam(required=false)String comment){adminService.createCampaign(code,name,comment);return redirect();}
    @PostMapping("/admin/campaigns/{id}/update") public String campaignUpdate(@PathVariable long id,@RequestParam String name,@RequestParam(required=false)String comment,@RequestParam(defaultValue="false")boolean enabled){adminService.updateCampaign(id,name,comment,enabled);return redirect();}
    @PostMapping("/admin/payments/{id}/check") public String paymentCheck(@PathVariable UUID id){adminService.checkPayment(id);return redirect();}
    private String redirect(){return "redirect:/admin";}
    private String redirectDevices(){return "redirect:/admin#devices";}
}
