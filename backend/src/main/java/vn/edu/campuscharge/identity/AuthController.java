package vn.edu.campuscharge.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!simulator")
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }
    public record RegisterRequest(String email,String password) {}
    public record CsrfView(String token,String headerName) {}
    @GetMapping("/csrf") CsrfView csrf(CsrfToken token) { return new CsrfView(token.getToken(),token.getHeaderName()); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    AuthService.UserView register(@RequestBody RegisterRequest request) { return service.register(request.email(),request.password()); }
}
