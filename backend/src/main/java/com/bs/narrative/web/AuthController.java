package com.bs.narrative.web;
import com.bs.narrative.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/auth") @CrossOrigin
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) {this.auth=auth;}
    public record LoginRequest(@NotBlank @Size(max=50) String username,@NotBlank @Size(max=72) String password) {}
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody LoginRequest req) {return auth.login(req.username(),req.password());}
    public record RegisterRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,30}") String username,
            @NotBlank @Size(min=8,max=72) String password) {}
    @PostMapping("/register") public Map<String,Object> register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req.username(),req.password());
    }
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest req) {return auth.account((String)req.getAttribute("authenticatedPlayerId"));}
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req) {
        auth.logout(req.getHeader("Authorization").substring(7));return Map.of("loggedOut",true);
    }
}
