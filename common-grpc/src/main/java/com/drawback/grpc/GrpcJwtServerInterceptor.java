package com.drawback.grpc;

import com.drawback.security.AuthenticatedUser;
import com.drawback.security.JwtService;
import com.drawback.security.TokenBlacklist;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * gRPC counterpart of the HTTP JwtAuthenticationFilter: no valid, non-revoked JWT => UNAUTHENTICATED.
 * The authenticated user is available to service implementations via {@link #CURRENT_USER}.
 */
public class GrpcJwtServerInterceptor implements ServerInterceptor {

    public static final Context.Key<AuthenticatedUser> CURRENT_USER = Context.key("drawback-user");

    private final JwtService jwtService;
    private final TokenBlacklist blacklist;

    public GrpcJwtServerInterceptor(JwtService jwtService, TokenBlacklist blacklist) {
        this.jwtService = jwtService;
        this.blacklist = blacklist;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String header = headers.get(BearerTokenCredentials.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return reject(call, "Missing bearer token");
        }
        try {
            AuthenticatedUser user = jwtService.parse(header.substring(7).trim());
            if (blacklist.isRevoked(user.tokenId())) {
                return reject(call, "Token revoked");
            }
            Context ctx = Context.current().withValue(CURRENT_USER, user);
            return Contexts.interceptCall(ctx, call, headers, next);
        } catch (RuntimeException e) {
            return reject(call, "Invalid or expired token");
        }
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, String why) {
        call.close(Status.UNAUTHENTICATED.withDescription(why), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
