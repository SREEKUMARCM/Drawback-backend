package com.drawback.security;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Drop-in security for every microservice: add the dependency and set drawback.security.jwt.secret.
 * Beans back off when a service defines its own.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({JwtProperties.class, SecurityPathsProperties.class})
@EnableMethodSecurity // @PreAuthorize / @PostAuthorize on service and controller methods
public class DrawbackSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    JwtService jwtService(JwtProperties properties, Clock clock) {
        return new JwtService(properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean(TokenBlacklist.class)
    @ConditionalOnClass(StringRedisTemplate.class)
    TokenBlacklist redisTokenBlacklist(StringRedisTemplate redis, Clock clock) {
        return new RedisTokenBlacklist(redis, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return new JwtAuthenticationEntryPoint();
    }

    /** ADMIN implies MODERATOR implies USER, so annotations can say hasRole('MODERATOR') and admins pass. */
    @Bean
    @ConditionalOnMissingBean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("MODERATOR")
                .role("MODERATOR").implies("USER")
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain drawbackSecurityFilterChain(HttpSecurity http,
                                                    JwtService jwtService,
                                                    TokenBlacklist blacklist,
                                                    JwtAuthenticationEntryPoint entryPoint,
                                                    SecurityPathsProperties paths) throws Exception {
        // The filter is created here (not exposed as a @Bean) so Boot does not also register it as a servlet filter.
        var jwtFilter = new JwtAuthenticationFilter(jwtService, blacklist);
        String[] publicPaths = paths.publicPaths().toArray(String[]::new);
        String[] publicReadPaths = paths.publicReadPaths().toArray(String[]::new);

        http
            .csrf(AbstractHttpConfigurer::disable)              // stateless + bearer header => no ambient cookie credentials
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(entryPoint))
            .headers(h -> h
                .contentTypeOptions(c -> {})
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(a -> {
                a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll(); // CORS pre-flight carries no credentials
                a.requestMatchers(publicPaths).permitAll();
                // An empty pattern list would match EVERY GET request, so only register it when it is non-empty.
                if (publicReadPaths.length > 0) a.requestMatchers(HttpMethod.GET, publicReadPaths).permitAll();
                a.anyRequest().authenticated();                 // <- every other call needs a valid JWT
            })
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
