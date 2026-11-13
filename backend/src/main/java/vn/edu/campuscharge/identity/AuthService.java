package vn.edu.campuscharge.identity;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.campuscharge.api.ApiAccess;

@Service
@Profile("!simulator")
public class AuthService implements UserDetailsService {
    public record UserView(UUID userId,String email,UserRole role,boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    public AuthService(JdbcTemplate jdbc,PasswordEncoder passwords) { this.jdbc=jdbc; this.passwords=passwords; }
    private static String email(String value) { return value==null?"":value.trim().toLowerCase(Locale.ROOT); }
    public UserView register(String address,String password) {
        String email=email(address);
        if(email.length()>254 || !email.matches("[^\\s@]+@[^\\s@]+") || password==null || password.length()<8 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Valid email and password of 8 to 72 UTF-8 bytes are required");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash) VALUES (?,?,?)",id,email,passwords.encode(password));
        return find(id);
    }
    public UserView find(UUID id) {
        var rows=jdbc.query("SELECT id,email,role,enabled FROM users WHERE id=?",(r,n)->new UserView(r.getObject("id",UUID.class),r.getString("email"),UserRole.valueOf(r.getString("role")),r.getBoolean("enabled")),id);
        if(rows.isEmpty()) throw ApiAccess.missing();
        return rows.getFirst();
    }
    public UserView current(Authentication authentication) { return find(ApiAccess.user(authentication)); }
    public boolean allowed(Authentication authentication,UserRole... roles) {
        if(authentication==null || !authentication.isAuthenticated()) return false;
        UUID id; try { id=UUID.fromString(authentication.getName()); } catch(IllegalArgumentException invalid) { return false; }
        var rows=jdbc.query("SELECT id,email,role,enabled FROM users WHERE id=?",(r,n)->new UserView(r.getObject("id",UUID.class),r.getString("email"),UserRole.valueOf(r.getString("role")),r.getBoolean("enabled")),id);
        return !rows.isEmpty() && rows.getFirst().enabled() && (roles.length==0 || Arrays.asList(roles).contains(rows.getFirst().role()));
    }
    @Override public UserDetails loadUserByUsername(String address) {
        var rows=jdbc.query("SELECT * FROM users WHERE email=?",(r,n)->User.withUsername(r.getObject("id",UUID.class).toString()).password(r.getString("password_hash"))
            .roles(r.getString("role")).disabled(!r.getBoolean("enabled")).build(),email(address));
        if(rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        return rows.getFirst();
    }
}
