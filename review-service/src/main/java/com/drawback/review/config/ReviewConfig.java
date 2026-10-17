package com.drawback.review.config;

import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.review.web.Dtos.SubjectResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ReviewConfig {

    /**
     * Parallel I/O fan-out for request handling (DB + gRPC). Bounded on purpose: when the queue is full the request is
     * rejected (-> 503) instead of piling up latency. Sized for blocking calls, hence more threads than cores.
     */
    @Bean(name = "ioExecutor")
    ThreadPoolTaskExecutor ioExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("review-io-");
        ex.setCorePoolSize(16);
        ex.setMaxPoolSize(64);
        ex.setQueueCapacity(500);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        return ex;
    }

    /** CPU/DB-bound batch work (reconciliation). CallerRuns = natural back-pressure on the submitting thread. */
    @Bean(name = "batchExecutor")
    ThreadPoolTaskExecutor batchExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        var ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("review-batch-");
        ex.setCorePoolSize(Math.max(2, cores));
        ex.setMaxPoolSize(Math.max(2, cores));
        ex.setQueueCapacity(100);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(60);
        return ex;
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    /**
     * Typed JSON per cache (records are final, so polymorphic default typing cannot be used).
     * Short TTLs are a safety net; entries are also evicted explicitly on every write.
     */
    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory cf, ObjectMapper mapper) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("review-svc:")
                .disableCachingNullValues();
        return RedisCacheManager.builder(cf)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
                .withCacheConfiguration(CacheNames.REVIEWS, typed(base, mapper, ReviewResponse.class, Duration.ofMinutes(10)))
                .withCacheConfiguration(CacheNames.SUBJECTS, typed(base, mapper, SubjectResponse.class, Duration.ofMinutes(2)))
                .build();
    }

    private static <T> RedisCacheConfiguration typed(RedisCacheConfiguration base, ObjectMapper mapper, Class<T> type, Duration ttl) {
        return base.entryTtl(ttl).serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, type)));
    }

    public static final class CacheNames {
        public static final String REVIEWS = "reviews";
        public static final String SUBJECTS = "subjects";
        private CacheNames() {}
    }

}
