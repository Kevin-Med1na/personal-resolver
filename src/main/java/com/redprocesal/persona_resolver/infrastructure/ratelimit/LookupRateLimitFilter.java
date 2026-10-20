package com.redprocesal.persona_resolver.infrastructure.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita las consultas a /api/lookup: por cliente (por minuto y por día) y en
 * total (por minuto). Frena la consulta masiva de documentos y protege a las
 * fuentes, a las que una sola cédula puede costarles varias sesiones.
 *
 * El cliente se identifica por su IP, que solo se usa en memoria para contar:
 * no se registra ni se guarda.
 */
@Component
public class LookupRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LookupRateLimitFilter.class);

    private static final String LOOKUP_PATH = "/api/lookup";
    private static final String GLOBAL_KEY = "*";

    /*
     * Detrás de Vercel la IP del cliente llega en estas cabeceras; Vercel
     * sobrescribe las que mande el cliente. Quien llame al backend sin pasar
     * por Vercel podría falsearlas, y para eso está el límite global.
     */
    private static final List<String> CLIENT_IP_HEADERS =
            List.of("x-vercel-forwarded-for", "x-real-ip", "x-forwarded-for");

    private final FixedWindowRateLimiter perClientPerMinute;
    private final FixedWindowRateLimiter perClientPerDay;
    private final FixedWindowRateLimiter globalPerMinute;
    private final ObjectMapper objectMapper;
    private final Set<String> reportedSources = ConcurrentHashMap.newKeySet();

    @Autowired
    public LookupRateLimitFilter(
            @Value("${app.rate-limit.per-client-per-minute:10}") int perClientPerMinute,
            @Value("${app.rate-limit.per-client-per-day:200}") int perClientPerDay,
            @Value("${app.rate-limit.global-per-minute:60}") int globalPerMinute,
            ObjectMapper objectMapper) {
        this(perClientPerMinute, perClientPerDay, globalPerMinute, objectMapper, Clock.systemUTC());
    }

    LookupRateLimitFilter(
            int perClientPerMinute,
            int perClientPerDay,
            int globalPerMinute,
            ObjectMapper objectMapper,
            Clock clock) {
        this.perClientPerMinute = new FixedWindowRateLimiter(perClientPerMinute, Duration.ofMinutes(1), clock);
        this.perClientPerDay = new FixedWindowRateLimiter(perClientPerDay, Duration.ofDays(1), clock);
        this.globalPerMinute = new FixedWindowRateLimiter(globalPerMinute, Duration.ofMinutes(1), clock);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && LOOKUP_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String client = clientKey(request);

        long retryAfter = perClientPerMinute.tryAcquire(client);
        if (retryAfter == 0) {
            retryAfter = perClientPerDay.tryAcquire(client);
        }
        if (retryAfter == 0) {
            retryAfter = globalPerMinute.tryAcquire(GLOBAL_KEY);
        }

        if (retryAfter > 0) {
            reject(response, retryAfter);
            return;
        }

        chain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        for (String header : CLIENT_IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                reportSource(header);
                return value.split(",")[0].trim();
            }
        }

        reportSource("la conexión (sin cabeceras de proxy)");
        return request.getRemoteAddr();
    }

    /** Una línea por origen, para comprobar de dónde sale la IP sin registrar ninguna. */
    private void reportSource(String source) {
        if (reportedSources.add(source)) {
            LOGGER.info("Límite de consultas: la IP del cliente se toma de {}.", source);
        }
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", "Demasiadas consultas seguidas. Intenta de nuevo en "
                + retryAfterSeconds + " segundos.");

        response.setStatus(status.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
