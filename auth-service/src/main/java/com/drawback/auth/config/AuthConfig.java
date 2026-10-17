package com.drawback.auth.config;

import com.drawback.grpc.GrpcJwtServerInterceptor;
import com.drawback.security.JwtService;
import com.drawback.security.TokenBlacklist;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AuthConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** Every gRPC method on this server requires the same JWT the HTTP API requires. */
    @Bean
    @GrpcGlobalServerInterceptor
    ServerInterceptor grpcJwtInterceptor(JwtService jwtService, TokenBlacklist blacklist) {
        return new GrpcJwtServerInterceptor(jwtService, blacklist);
    }
}
