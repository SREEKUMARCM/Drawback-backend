package com.drawback.review.client;

import com.drawback.grpc.BearerTokenCredentials;
import com.drawback.grpc.user.GetUserRequest;
import com.drawback.grpc.user.UserDirectoryGrpc;
import com.drawback.grpc.user.UserProfile;
import com.drawback.grpc.user.UserStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Thin gRPC wrapper: per-call deadline, caller's JWT forwarded, transport errors mapped to an explicit result. */
@Component
public class UserDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryClient.class);

    public enum Outcome { FOUND, NOT_FOUND, UNAVAILABLE }

    public record Lookup(Outcome outcome, UserProfile profile) {
        public boolean isActiveUser() {
            return outcome == Outcome.FOUND && profile.getStatus() == UserStatus.ACTIVE;
        }
    }

    @GrpcClient("auth-service")
    private UserDirectoryGrpc.UserDirectoryBlockingStub stub;

    /** For tests: allows injecting a stub without a Spring context. */
    public UserDirectoryClient() {}

    public UserDirectoryClient(UserDirectoryGrpc.UserDirectoryBlockingStub stub) {
        this.stub = stub;
    }

    public Lookup find(UUID userId, String bearerToken, long deadlineMillis) {
        try {
            UserProfile p = stub.withCallCredentials(new BearerTokenCredentials(bearerToken))
                    .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                    .getUser(GetUserRequest.newBuilder().setUserId(userId.toString()).build());
            return new Lookup(Outcome.FOUND, p);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) return new Lookup(Outcome.NOT_FOUND, null);
            log.warn("auth-service lookup failed: {}", e.getStatus());
            return new Lookup(Outcome.UNAVAILABLE, null);
        }
    }
}
