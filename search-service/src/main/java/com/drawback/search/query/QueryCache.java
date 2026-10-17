package com.drawback.search.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Short-TTL result cache in Redis that absorbs bursts of identical (popular) queries.
 * Redis trouble never fails a search: any cache error falls through to Solr.
 */
@Component
public class QueryCache {

    private static final Logger log = LoggerFactory.getLogger(QueryCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public QueryCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public <T> T get(String keyMaterial, Class<T> type, Duration ttl, Supplier<T> loader) {
        String key = "search:q:" + sha1(keyMaterial);
        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) return mapper.readValue(hit, type);
        } catch (Exception e) {
            log.debug("query cache read failed: {}", e.toString());
        }
        T value = loader.get();
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("query cache write failed: {}", e.toString());
        }
        return value;
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
