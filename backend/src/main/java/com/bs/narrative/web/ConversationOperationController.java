package com.bs.narrative.web;

import com.bs.narrative.service.ConversationOperationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/game/operations")
public class ConversationOperationController {
    private final ConversationOperationService operations;
    public ConversationOperationController(ConversationOperationService operations){this.operations=operations;}
    @PostMapping public org.springframework.http.ResponseEntity<Map<String,Object>> create(@RequestBody ConversationOperationService.Request request,HttpServletRequest http) {
        return org.springframework.http.ResponseEntity.accepted().body(operations.submit(new ConversationOperationService.Request(request.operationId(),request.sessionId(),
            (String)http.getAttribute("authenticatedPlayerId"),request.sceneId(),request.playerText(),request.dialogue(),request.branchEpoch(),request.expectedRevision(),request.storyMode(),request.narrativeMode())));
    }
    @GetMapping("/{id}") public Map<String,Object> read(@PathVariable String id,@RequestParam String sessionId,
            @RequestParam(required=false) Long branchEpoch,HttpServletRequest http) {
        return operations.read(sessionId,(String)http.getAttribute("authenticatedPlayerId"),id,branchEpoch);
    }
    @PostMapping("/{id}/input-review") public org.springframework.http.ResponseEntity<Map<String,Object>> review(@PathVariable String id,@RequestParam String sessionId,@RequestParam long branchEpoch,@RequestBody Map<String,String> request,HttpServletRequest http){
        return org.springframework.http.ResponseEntity.accepted().body(operations.reviewInput(sessionId,(String)http.getAttribute("authenticatedPlayerId"),id,branchEpoch,request.get("reviewId"),request.get("choice"),true));
    }
    @PostMapping("/{id}/recover") public org.springframework.http.ResponseEntity<Map<String,Object>> recover(@PathVariable String id,@RequestParam String sessionId,
            @RequestParam long branchEpoch,HttpServletRequest http) {
        return org.springframework.http.ResponseEntity.accepted().body(operations.recoverAsync(sessionId,(String)http.getAttribute("authenticatedPlayerId"),id,branchEpoch));
    }
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id,@RequestParam String sessionId,
            @RequestParam long branchEpoch,HttpServletRequest http) {
        return operations.cancel(sessionId,(String)http.getAttribute("authenticatedPlayerId"),id,branchEpoch);
    }
}
