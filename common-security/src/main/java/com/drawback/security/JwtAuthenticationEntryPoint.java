package com.drawback.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.access.AccessDeniedException;

/** Uniform RFC 7807 bodies for 401 (no/invalid token) and 403 (authenticated but not allowed). */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String ERROR_ATTRIBUTE = "drawback.auth.error";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        Object reason = request.getAttribute(ERROR_ATTRIBUTE);
        String detail = reason != null ? reason.toString() : "Authentication required";
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"drawback\"");
        write(response, HttpStatus.UNAUTHORIZED, detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
    }

    private static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
