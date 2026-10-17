package com.drawback.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drawback.events.ReviewEvent;
import com.drawback.events.SubjectEvent;
import com.drawback.events.Topics;
import com.drawback.grpc.user.UserProfile;
import com.drawback.grpc.user.UserStatus;
import com.drawback.review.client.UserDirectoryClient;
import com.drawback.review.client.UserDirectoryClient.Lookup;
import com.drawback.review.client.UserDirectoryClient.Outcome;
import com.drawback.review.service.ReconciliationService;
import com.drawback.security.JwtService;
import com.drawback.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** End to end against real PostgreSQL, Redis and Kafka (needs Docker): HTTP -> DB -> outbox -> Kafka. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "drawback.security.jwt.secret=ZHJhd2JhY2stdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OS1hYmNkZWY=",
        "drawback.outbox.poll-ms=200"
})
@AutoConfigureMockMvc
class ReviewFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired ReconciliationService reconciliation;
    @MockitoBean UserDirectoryClient userDirectory;

    private String token(UUID id, boolean verified, Role... roles) {
        Set<Role> set = EnumSet.noneOf(Role.class);
        set.addAll(List.of(roles.length == 0 ? new Role[]{Role.USER} : roles));
        return jwt.issueAccessToken(id, "u" + id.toString().substring(0, 6), set, verified).value();
    }

    private JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String bearer, Object body, int expected) throws Exception {
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var res = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? json.createObjectNode() : json.readTree(res);
    }

    private static Map<String, Object> review(int rating) {
        return Map.of("title", "Honest verdict", "body", "Used it for a month, here are my detailed thoughts on it.", "rating", rating);
    }

    private void accountsAreActive() {
        var active = UserProfile.newBuilder().setStatus(UserStatus.ACTIVE).build();
        org.mockito.Mockito.when(userDirectory.find(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong()))
                .thenReturn(new Lookup(Outcome.FOUND, active));
    }

    @Test
    void reviewLifecycleKeepsAggregateCorrectAndPublishesEventsToKafka() throws Exception {
        accountsAreActive();
        UUID owner = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

        mvc.perform(get("/api/v1/subjects/" + UUID.randomUUID())).andExpect(status().isUnauthorized());

        JsonNode subject = send(post("/api/v1/subjects"), token(owner, true), Map.of("name", "Phone X", "category", "smartphones", "description", "A phone"), 201);
        String sid = subject.get("id").asText();

        send(post("/api/v1/subjects/" + sid + "/reviews"), token(b, true), review(80), 201);
        JsonNode second = send(post("/api/v1/subjects/" + sid + "/reviews"), token(c, true), review(40), 201);

        send(post("/api/v1/subjects/" + sid + "/reviews"), token(b, true), review(10), 409);          // one review per user
        send(post("/api/v1/subjects/" + sid + "/reviews"), token(owner, true), review(100), 403);       // not your own subject
        send(post("/api/v1/subjects/" + sid + "/reviews"), token(UUID.randomUUID(), false), review(50), 403); // unverified
        send(post("/api/v1/subjects/" + sid + "/reviews"), token(UUID.randomUUID(), true),
                Map.of("title", "x", "body", "short", "rating", 150), 400);                              // validation

        mvc.perform(get("/api/v1/subjects/" + sid).header("Authorization", "Bearer " + token(b, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(2))
                .andExpect(jsonPath("$.averageRating").value(60.0));

        // delete by a moderator removes the rating from the mean: only 80 remains
        send(delete("/api/v1/reviews/" + second.get("id").asText()), token(UUID.randomUUID(), true, Role.MODERATOR), null, 204);
        mvc.perform(get("/api/v1/subjects/" + sid).header("Authorization", "Bearer " + token(b, true)))
                .andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.averageRating").value(80.0));

        // events arrive on Kafka via the outbox
        try (var consumer = consumer()) {
            consumer.subscribe(List.of(Topics.REVIEWS, Topics.SUBJECTS));
            List<ReviewEvent> reviewEvents = new ArrayList<>();
            List<SubjectEvent> subjectEvents = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    try {
                        if (r.topic().equals(Topics.REVIEWS)) reviewEvents.add(json.readValue(r.value(), ReviewEvent.class));
                        else subjectEvents.add(json.readValue(r.value(), SubjectEvent.class));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                assertThat(reviewEvents).extracting(ReviewEvent::type)
                        .contains(ReviewEvent.Type.REVIEW_CREATED, ReviewEvent.Type.REVIEW_DELETED);
                assertThat(subjectEvents).isNotEmpty();
                assertThat(subjectEvents.get(subjectEvents.size() - 1).averageRating()).isEqualTo(80.0);
            });
        }
    }

    @Test
    void concurrentReviewersNeverLoseAnUpdateOnTheAggregate() throws Exception {
        accountsAreActive();
        String sid = send(post("/api/v1/subjects"), token(UUID.randomUUID(), true),
                Map.of("name", "Busy Product", "category", "other"), 201).get("id").asText();

        int n = 40;
        var pool = Executors.newFixedThreadPool(16);
        var start = new CountDownLatch(1);
        long expectedSum = 0;
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int rating = (i * 5) % 101;
            expectedSum += rating;
            String bearer = token(UUID.randomUUID(), true);
            futures.add(pool.submit(() -> {
                start.await();
                send(post("/api/v1/subjects/" + sid + "/reviews"), bearer, review(rating), 201);
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        JsonNode s = send(get("/api/v1/subjects/" + sid), token(UUID.randomUUID(), true), null, 200);
        assertThat(s.get("reviewCount").asInt()).isEqualTo(n);
        assertThat(s.get("averageRating").asDouble()).isEqualTo(Math.round(expectedSum * 10.0 / n) / 10.0);

        // the reconciliation job independently recomputes from the rows and must find nothing to correct
        assertThat(reconciliation.reconcileAll().subjectsCorrected()).isZero();
    }

    private KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(p);
    }
}
