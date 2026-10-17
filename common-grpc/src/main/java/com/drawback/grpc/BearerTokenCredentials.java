package com.drawback.grpc;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import java.util.concurrent.Executor;

/**
 * Forwards the caller's JWT on an outgoing gRPC call: {@code stub.withCallCredentials(new BearerTokenCredentials(jwt))}.
 * The token is passed explicitly (not read from a ThreadLocal) so it also works on worker threads.
 */
public final class BearerTokenCredentials extends CallCredentials {

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String token;

    public BearerTokenCredentials(String token) {
        this.token = token;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        applier.apply(headers);
    }
}
