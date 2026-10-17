package com.drawback.review.drawback;

import com.drawback.review.drawback.ApiModels.AttachmentDto;
import com.drawback.review.drawback.ApiModels.AttachmentInput;
import com.drawback.security.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Evidence uploads (screenshots, photos, short videos).
 *
 * Storage: files on disk under {@code drawback.media.dir} (mount a persistent volume, or swap this class for an S3 client);
 * only metadata is in PostgreSQL. Type is checked twice: declared content type against an allow-list, and the file's magic
 * bytes against the declared type. SVG/HTML are not allowed, so an upload can never carry script.
 *
 * Reading: {@code <img>} and {@code <video>} elements cannot send an Authorization header, so each file has an unguessable
 * capability URL ({@code /api/v1/media/files/{id}?sig=...}); the signature is an HMAC of the id under the server secret.
 * This is the one deliberate exception to "every call carries a JWT".
 */
@Service
public class MediaService {

    private static final Map<String, String> KIND_BY_TYPE = Map.of(
            "image/jpeg", "image", "image/png", "image", "image/webp", "image", "image/gif", "image",
            "video/mp4", "video", "video/webm", "video", "video/quicktime", "video");

    public record StoredFile(Path path, String contentType, String name) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final Path dir;
    private final long maxBytes;
    private final byte[] secret;

    public MediaService(NamedParameterJdbcTemplate jdbc,
                        @Value("${drawback.media.dir:./data/media}") String dir,
                        @Value("${drawback.media.max-bytes:26214400}") long maxBytes,
                        @Value("${drawback.security.jwt.secret}") String secret) {
        this.jdbc = jdbc;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public AttachmentDto store(MultipartFile file, UUID owner) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Choose a file to upload");
        if (file.getSize() > maxBytes) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large (max " + maxBytes / 1_048_576 + " MB)");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String kind = KIND_BY_TYPE.get(type);
        if (kind == null) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG, WebP, GIF, MP4, WebM and MOV files are accepted");

        UUID id = UUID.randomUUID();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "upload-", ".part");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] head = new byte[16];
            try (InputStream in = Files.newInputStream(tmp)) {
                int n = in.readNBytes(head, 0, head.length);
                if (n < 12 || !magicMatches(type, head)) {
                    Files.deleteIfExists(tmp);
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The file content does not match its type");
                }
            }
            Files.move(tmp, dir.resolve(id.toString()), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the file");
        }
        String name = sanitiseName(file.getOriginalFilename());
        jdbc.update("INSERT INTO media (id, owner_id, kind, content_type, original_name, size_bytes) VALUES (:id, :o, :k, :t, :n, :s)",
                new MapSqlParameterSource("id", id).addValue("o", owner).addValue("k", kind).addValue("t", type)
                        .addValue("n", name).addValue("s", file.getSize()));
        return new AttachmentDto(id.toString(), kind, urlFor(id), null, null, name);
    }

    /** Turns client-supplied attachment references into trusted rows: each must be media uploaded by the same user. */
    public List<AttachmentDto> resolve(UUID owner, List<AttachmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<AttachmentDto> out = new ArrayList<>();
        for (AttachmentInput in : inputs) {
            UUID id;
            try {
                id = UUID.fromString(in.id());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown attachment");
            }
            var rows = jdbc.query("SELECT kind, original_name FROM media WHERE id = :id AND owner_id = :o",
                    new MapSqlParameterSource("id", id).addValue("o", owner),
                    (rs, n) -> new String[] {rs.getString("kind"), rs.getString("original_name")});
            if (rows.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown attachment");
            String caption = in.caption() == null || in.caption().isBlank() ? null : in.caption().trim();
            out.add(new AttachmentDto(id.toString(), rows.get(0)[0], urlFor(id), null, caption, rows.get(0)[1]));
        }
        return out;
    }

    /** @return the file when the signature is valid and the file exists */
    public Optional<StoredFile> open(UUID id, String sig) {
        if (sig == null || !constantTimeEquals(signature(id), sig)) return Optional.empty();
        var rows = jdbc.query("SELECT content_type, original_name FROM media WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, n) -> new String[] {rs.getString("content_type"), rs.getString("original_name")});
        if (rows.isEmpty()) return Optional.empty();
        Path path = dir.resolve(id.toString()).normalize();
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(new StoredFile(path, rows.get(0)[0], rows.get(0)[1]));
    }

    // ---------------------------------------------------------------------------------------------------------------

    String urlFor(UUID id) {
        return "/api/v1/media/files/" + id + "?sig=" + signature(id);
    }

    String signature(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(("media:" + id).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static boolean magicMatches(String type, byte[] h) {
        return switch (type) {
            case "image/jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            case "image/png" -> (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            case "image/gif" -> h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
            case "image/webp" -> h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
            case "video/mp4", "video/quicktime" -> h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
            case "video/webm" -> (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
            default -> false;
        };
    }

    private static String sanitiseName(String original) {
        String n = original == null ? "upload" : original.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "upload";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
