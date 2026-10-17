package com.drawback.security;

import java.time.Instant;

/** Revoked access-token ids (jti). Entries only need to live until the token would have expired anyway. */
public interface TokenBlacklist {

    void revoke(String tokenId, Instant tokenExpiresAt);

    boolean isRevoked(String tokenId);
}
