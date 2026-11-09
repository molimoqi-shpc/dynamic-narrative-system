package com.bs.narrative.web;

import com.bs.narrative.service.MainModelService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@RestController @RequestMapping("/api/game/main-model")
public class MainModelController {
 private final MainModelService models;
 public MainModelController(MainModelService models){this.models=models;}
 @GetMapping public Map<String,Object> read(@RequestParam String sessionId){return models.read(sessionId);}
 public record Choice(@NotBlank String sessionId,@NotBlank String model){}
 @PostMapping public Map<String,Object> change(@Valid @RequestBody Choice choice){return models.change(choice.sessionId(),choice.model());}
}
