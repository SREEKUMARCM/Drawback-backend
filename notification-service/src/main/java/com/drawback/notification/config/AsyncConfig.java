package com.drawback.notification.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /** Delivery is slow, external I/O: keep it off the Kafka consumer thread, bounded, with back-pressure. */
    @Bean(name = "notificationExecutor")
    ThreadPoolTaskExecutor notificationExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("notify-");
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(1000);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        return ex;
    }
}
