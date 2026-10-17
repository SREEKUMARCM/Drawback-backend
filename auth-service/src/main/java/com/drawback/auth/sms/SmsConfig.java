package com.drawback.auth.sms;

import com.drawback.security.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
public class SmsConfig {

    private static final Logger log = LoggerFactory.getLogger(SmsConfig.class);

    /**
     * LOCAL DEVELOPMENT ONLY: writes the code to the service log instead of sending an SMS.
     * Enable with drawback.auth.otp.dev-log=true (docker-compose does). Never enable it in production.
     */
    @Bean
    @ConditionalOnProperty(name = "drawback.auth.otp.dev-log", havingValue = "true")
    SmsSender loggingSmsSender() {
        return (phone, message) -> log.warn("[DEV SMS] to ***{}: {}", phone.substring(Math.max(0, phone.length() - 3)), message);
    }

    /** Fail loudly (503) rather than pretend a code was sent when no provider is configured. */
    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender unconfiguredSmsSender() {
        return (phone, message) -> {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS delivery is not configured on this server");
        };
    }
}
