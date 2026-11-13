package vn.edu.campuscharge.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@Profile("!simulator")
public class VnPaySecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain vnpayIpn(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/payments/vnpay/ipn")
            .authorizeHttpRequests(routes->routes.anyRequest().permitAll())
            .csrf(csrf->csrf.disable())
            .requestCache(cache->cache.disable());
        return http.build();
    }
}
