package dev.anmol.webhooks.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the operational endpoints under /admin. These expose the dead-letter
 * queue and a replay trigger, so they must not be world-readable.
 *
 * Auth is a shared API key sent in the X-Admin-Api-Key header. The key is
 * compared with MessageDigest.isEqual (constant time): a plain String.equals
 * early-exits on the first mismatching byte and leaks timing that lets an
 * attacker recover the key byte by byte, the same reason webhook signatures
 * use constant-time comparison.
 *
 * This is intentionally a plain servlet filter scoped to /admin (see
 * SecurityConfig) rather than pulling in Spring Security, which would secure
 * every endpoint by default and change behaviour of the webhook path.
 */
public class AdminApiKeyFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Admin-Api-Key";

    private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);

    private final byte[] expectedKey;

    public AdminApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String apiKeyHeader = request.getHeader(API_KEY_HEADER);
        if (apiKeyHeader == null || !matches(apiKeyHeader)) {
            log.warn("Rejected unauthenticated admin request: {} {}",
                    request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + API_KEY_HEADER + "\"");
            response.getWriter().write("{\"error\":\"admin authentication required\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }
}
