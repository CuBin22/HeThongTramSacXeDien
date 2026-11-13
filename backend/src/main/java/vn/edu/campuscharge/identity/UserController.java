package vn.edu.campuscharge.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!simulator")
public class UserController {
    private final AuthService users;
    public UserController(AuthService users) { this.users=users; }
    @GetMapping("/api/auth/me") AuthService.UserView me(Authentication authentication) { return users.current(authentication); }
}
