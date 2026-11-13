package vn.edu.campuscharge.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@Profile("!simulator")
public class SecurityConfiguration {
    @Bean static PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http,AuthService users,ObjectMapper json) throws Exception {
        http.authorizeHttpRequests(routes->routes
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers("/api/auth/csrf","/api/auth/register","/api/auth/login").permitAll()
            .requestMatchers("/api/admin/**").access((auth,context)->new AuthorizationDecision(users.allowed(auth.get(),UserRole.ADMIN)))
            .requestMatchers(HttpMethod.POST,"/api/incidents/report").access((auth,context)->new AuthorizationDecision(users.allowed(auth.get(),UserRole.USER)))
            .requestMatchers("/api/my/incidents/**").access((auth,context)->new AuthorizationDecision(users.allowed(auth.get(),UserRole.USER)))
            .requestMatchers("/api/incidents/**").access((auth,context)->new AuthorizationDecision(users.allowed(auth.get(),UserRole.ADMIN)))
            .requestMatchers("/api/**").access((auth,context)->new AuthorizationDecision(users.allowed(auth.get())))
            .anyRequest().denyAll());
        // Session-backed CSRF tokens are fetched by the SPA, then sent in X-CSRF-TOKEN for every mutation.
        http.formLogin(login->login.loginProcessingUrl("/api/auth/login").usernameParameter("email")
            .successHandler((request,response,auth)-> { response.setContentType("application/json"); json.writeValue(response.getOutputStream(),users.current(auth)); })
            .failureHandler((request,response,error)->response.sendError(401,"Invalid credentials")));
        http.logout(logout->logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID").logoutSuccessHandler((request,response,auth)->response.setStatus(204)));
        http.exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->response.sendError(401,"Authentication required"))
            .accessDeniedHandler((request,response,error)->response.sendError(403,"Access denied")));
        http.requestCache(cache->cache.disable()).httpBasic(basic->basic.disable());
        return http.build();
    }
}
