package com.bs.narrative.web;

import com.bs.narrative.service.WorldBookBrowserService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/game/world-book")
public class WorldBookBrowserController {
    private final WorldBookBrowserService browser;
    public WorldBookBrowserController(WorldBookBrowserService browser){this.browser=browser;}

    @GetMapping("/plots")
    public Map<String,Object> plots(@RequestParam String sessionId,@RequestAttribute("authenticatedPlayerId") String playerId,
            @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String eventId,
            @RequestParam(defaultValue="") String category,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="0") int threadPage){
        return browser.browsePlots(sessionId,playerId,keyword,eventId,category,page,threadPage);
    }
    @GetMapping("/plots/passage")
    public Map<String,Object> passage(@RequestParam String sessionId,@RequestAttribute("authenticatedPlayerId") String playerId,@RequestParam long logId){
        return browser.plotPassage(sessionId,playerId,logId);
    }

    @GetMapping("/browse")
    public Map<String,Object> browse(@RequestParam String sessionId,
            @RequestAttribute("authenticatedPlayerId") String playerId,
            @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String roleId,
            @RequestParam(required=false) Long fromSeconds,@RequestParam(required=false) Long toSeconds,
            @RequestParam(defaultValue="0") int page){
        return browser.browse(sessionId,playerId,keyword,roleId,fromSeconds,toSeconds,page);
    }
}
