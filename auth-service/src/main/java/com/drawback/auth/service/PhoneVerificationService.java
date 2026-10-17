package com.drawback.auth.service;

import com.drawback.auth.domain.UserAccount;
import com.drawback.auth.repo.UserRepository;
import com.drawback.auth.sms.SmsSender;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that an account belongs to a real person with a phone. A verified account may vote.
 *
 * <ul>
 *   <li>The code is 6 random digits, valid 5 minutes, stored only as a peppered hash in Redis, tied to user AND number.</li>
 *   <li>Limits: one SMS per 30 s, 5 SMS per hour, 5 wrong guesses per code (then the code is burned).</li>
 *   <li>The number is stored as a peppered hash with a unique index: one number can verify only one account.</li>
 * </ul>
 */
@Service
public class PhoneVerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration RESEND_AFTER = Duration.ofSeconds(30);
    static final Duration HOURLY_WINDOW = Duration.ofHours(1);
    static final int MAX_SMS_PER_HOUR = 5;
    static final int MAX_GUESSES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final SmsSender sms;
    private final TransactionTemplate tx;
    private final String pepper;

    public PhoneVerificationService(StringRedisTemplate redis, UserRepository users, SmsSender sms, TransactionTemplate tx,
                                    @Value("${drawback.security.jwt.secret}") String pepper) {
        this.redis = redis;
        this.users = users;
        this.sms = sms;
        this.tx = tx;
        this.pepper = pepper;
    }

    /** @return the number of seconds until another code may be requested */
    public long requestCode(AuthenticatedUser caller, String rawPhone) {
        String digits = normalise(rawPhone);
        UUID id = caller.userId();

        if (Boolean.TRUE.equals(redis.hasKey(sentKey(id)))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another code");
        }
        String hourKey = hourKey(id);
        Long sent = redis.opsForValue().increment(hourKey);
        if (sent != null && sent == 1L) redis.expire(hourKey, HOURLY_WINDOW);
        if (sent != null && sent > MAX_SMS_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested. Try again later.");
        }
        // Do not spend an SMS on a number that already verified a different account.
        users.findByPhoneHash(hash("phone", digits))
                .filter(u -> !u.getId().equals(id))
                .ifPresent(u -> { throw ApiException.conflict("This number is already linked to another account"); });

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(codeKey(id), hash("code:" + id + ":" + digits, code), CODE_TTL);
        redis.delete(triesKey(id));
        redis.opsForValue().set(sentKey(id), "1", RESEND_AFTER);
        sms.send(digits, "Your Drawback verification code is " + code + ". It expires in 5 minutes.");
        return RESEND_AFTER.toSeconds();
    }

    /** Verifies the code and links the number. Returns the updated account. */
    public UserAccount verify(AuthenticatedUser caller, String rawPhone, String code) {
        String digits = normalise(rawPhone);
        UUID id = caller.userId();

        String stored = redis.opsForValue().get(codeKey(id));
        if (stored == null) throw ApiException.badRequest("That code has expired. Request a new one.");

        Long tries = redis.opsForValue().increment(triesKey(id));
        if (tries != null && tries == 1L) redis.expire(triesKey(id), CODE_TTL);
        if (tries != null && tries > MAX_GUESSES) {
            redis.delete(codeKey(id));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes. Request a new one.");
        }
        if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                hash("code:" + id + ":" + digits, code).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.badRequest("That code is incorrect. Check it and try again.");
        }
        redis.delete(codeKey(id));
        redis.delete(triesKey(id));

        try {
            return tx.execute(status -> {
                UserAccount user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
                users.findByPhoneHash(hash("phone", digits))
                        .filter(other -> !other.getId().equals(id))
                        .ifPresent(other -> { throw ApiException.conflict("This number is already linked to another account"); });
                user.linkPhone(hash("phone", digits), mask(digits));
                return users.saveAndFlush(user);
            });
        } catch (DataIntegrityViolationException race) { // two accounts verified the same number at once
            throw ApiException.conflict("This number is already linked to another account");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    static String normalise(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) throw ApiException.badRequest("Enter a valid phone number");
        return digits;
    }

    static String mask(String digits) {
        return "+" + digits.substring(0, Math.min(2, digits.length())) + " •••• ••" + digits.substring(digits.length() - 3);
    }

    private String hash(String scope, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((pepper + "|" + scope + "|" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String codeKey(UUID id) { return "otp:code:" + id; }
    private static String triesKey(UUID id) { return "otp:tries:" + id; }
    private static String sentKey(UUID id) { return "otp:sent:" + id; }
    private static String hourKey(UUID id) { return "otp:hour:" + id; }
}
