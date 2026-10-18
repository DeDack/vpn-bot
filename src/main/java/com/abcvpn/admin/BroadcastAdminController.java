package com.abcvpn.admin;

import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.abcvpn.enumeration.BroadcastSegment;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.service.BroadcastService;

@Controller
public class BroadcastAdminController {
    private final BroadcastService broadcastService;
    public BroadcastAdminController(BroadcastService broadcastService) {this.broadcastService=broadcastService;}
    @GetMapping(value="/admin/broadcasts",params="!draft")
    public String page(Model model) {model.addAttribute("page",broadcastService.page());return "admin/broadcasts";}
    @GetMapping(value="/admin/broadcasts",params="draft")
    public String draft(@RequestParam long draft,Model model) {model.addAttribute("page",broadcastService.page());model.addAttribute("draft",broadcastService.draft(draft));return "admin/broadcasts";}
    @PostMapping("/admin/broadcasts/preview")
    public String preview(@RequestParam String templateType,@RequestParam String messageText,@RequestParam(required=false) String buttonText,
                          @RequestParam(required=false) String buttonType,@RequestParam(required=false) String buttonValue,
                          @RequestParam(defaultValue="false") boolean marketing,@RequestParam Set<BroadcastSegment> segments,Model model) {
        model.addAttribute("page",broadcastService.page());model.addAttribute("draft",broadcastService.preview(templateType,messageText,buttonText,buttonType,buttonValue,marketing,segments));return "admin/broadcasts";
    }
    @PostMapping("/admin/broadcasts/{id}/test")
    public String test(@PathVariable long id) {broadcastService.sendTest(id);return "redirect:/admin/broadcasts?draft="+id;}
    @PostMapping("/admin/broadcasts/{id}/confirm")
    public String confirm(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirmed) {
        if(!confirmed) throw new FlowException(ErrorCode.INVALID_REQUEST,"Подтвердите массовую отправку");broadcastService.confirm(id);return "redirect:/admin/broadcasts";
    }
}
