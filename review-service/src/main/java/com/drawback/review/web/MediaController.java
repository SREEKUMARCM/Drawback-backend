package com.drawback.review.web;

import com.drawback.review.drawback.ApiModels.AttachmentDto;
import com.drawback.review.drawback.MediaService;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    /** Upload evidence. Requires a JWT. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentDto upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUser user) {
        return media.store(file, user.userId());
    }

    /** Public read, protected by the unguessable signed URL (see MediaService). Supports Range requests for video. */
    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> file(@PathVariable UUID id, @RequestParam(required = false) String sig) {
        var file = media.open(id, sig).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "File not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("evidence-" + id).build().toString())
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(new FileSystemResource(file.path()));
    }
}
