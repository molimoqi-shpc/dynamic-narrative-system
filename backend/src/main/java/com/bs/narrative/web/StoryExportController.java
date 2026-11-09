package com.bs.narrative.web;

import com.bs.narrative.service.StoryExportService;
import jakarta.servlet.http.*;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/game/story-export")
public class StoryExportController {
 private final StoryExportService export;
 public StoryExportController(StoryExportService export){this.export=export;}
 @GetMapping public void download(@RequestParam String sessionId,HttpServletRequest request,HttpServletResponse response)throws IOException{
  export.write(sessionId,(String)request.getAttribute("authenticatedPlayerId"),response.getOutputStream(),info->{
   response.setContentType("text/plain;charset=UTF-8");
   response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(info.filename(),StandardCharsets.UTF_8).build().toString());
   response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");
  });
 }
}
