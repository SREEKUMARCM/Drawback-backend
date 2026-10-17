package com.drawback.auth.grpc;

import com.drawback.auth.domain.UserAccount;
import com.drawback.auth.domain.UserStats;
import com.drawback.auth.repo.UserRepository;
import com.drawback.auth.repo.UserStatsRepository;
import com.drawback.grpc.user.GetUserRequest;
import com.drawback.grpc.user.GetUsersRequest;
import com.drawback.grpc.user.GetUsersResponse;
import com.drawback.grpc.user.UserDirectoryGrpc;
import com.drawback.grpc.user.UserProfile;
import com.drawback.grpc.user.UserStatus;
import com.drawback.security.Role;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

/** Internal user lookup used by review-service (and others) instead of sharing the user database. */
@GrpcService
public class UserDirectoryGrpcService extends UserDirectoryGrpc.UserDirectoryImplBase {

    private static final int MAX_BATCH = 100;

    private final UserRepository users;
    private final UserStatsRepository stats;

    public UserDirectoryGrpcService(UserRepository users, UserStatsRepository stats) {
        this.users = users;
        this.stats = stats;
    }

    @Override
    @Transactional(readOnly = true)
    public void getUser(GetUserRequest request, StreamObserver<UserProfile> out) {
        UUID id;
        try {
            id = UUID.fromString(request.getUserId());
        } catch (IllegalArgumentException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription("user_id must be a UUID").asRuntimeException());
            return;
        }
        var user = users.findById(id);
        if (user.isEmpty()) {
            out.onError(Status.NOT_FOUND.withDescription("user not found").asRuntimeException());
            return;
        }
        long count = stats.findById(id).map(UserStats::getReviewCount).orElse(0L);
        out.onNext(toProto(user.get(), count));
        out.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void getUsers(GetUsersRequest request, StreamObserver<GetUsersResponse> out) {
        if (request.getUserIdsCount() > MAX_BATCH) {
            out.onError(Status.INVALID_ARGUMENT.withDescription("max " + MAX_BATCH + " ids").asRuntimeException());
            return;
        }
        Set<UUID> ids = new HashSet<>();
        for (String s : request.getUserIdsList()) {
            try {
                ids.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                out.onError(Status.INVALID_ARGUMENT.withDescription("bad uuid: " + s).asRuntimeException());
                return;
            }
        }
        // two set-based queries instead of N lookups
        Map<UUID, Long> counts = stats.findAllByUserIdIn(ids).stream()
                .collect(Collectors.toMap(UserStats::getUserId, UserStats::getReviewCount));
        List<UserAccount> found = users.findAllByIdIn(ids);
        var response = GetUsersResponse.newBuilder();
        found.forEach(u -> response.addUsers(toProto(u, counts.getOrDefault(u.getId(), 0L))));
        out.onNext(response.build());
        out.onCompleted();
    }

    private static UserProfile toProto(UserAccount u, long reviewCount) {
        return UserProfile.newBuilder()
                .setId(u.getId().toString())
                .setUsername(u.getUsername())
                .setDisplayName(u.getDisplayName())
                .setVerified(u.isVerified())
                .setStatus(u.isActive() ? UserStatus.ACTIVE : UserStatus.SUSPENDED)
                .setReviewCount(reviewCount)
                .addAllRoles(u.getRoles().stream().map(Role::name).sorted().toList())
                .build();
    }
}
