package com.redprocesal.persona_resolver.infrastructure.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permite hasta {@code limit} eventos por clave en cada ventana fija de
 * {@code window}. Vive solo en memoria: al reiniciar el servicio se olvida
 * todo. Un límite de 0 o menos lo desactiva.
 */
final class FixedWindowRateLimiter {

    /** A partir de este número de claves se descartan las ventanas vencidas. */
    private static final int PURGE_THRESHOLD = 10_000;

    private record Window(long startMillis, int count) {
    }

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Registra un evento para {@code key}. Devuelve 0 si se permite, o los
     * segundos que faltan para que empiece la siguiente ventana.
     */
    long tryAcquire(String key) {
        if (limit <= 0) {
            return 0;
        }

        long now = clock.millis();
        long[] retryAfterSeconds = {0};

        windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            if (current.count() >= limit) {
                long remainingMillis = current.startMillis() + windowMillis - now;
                retryAfterSeconds[0] = Math.max(1, (remainingMillis + 999) / 1000);
                return current;
            }
            return new Window(current.startMillis(), current.count() + 1);
        });

        if (windows.size() > PURGE_THRESHOLD) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startMillis() >= windowMillis);
        }

        return retryAfterSeconds[0];
    }
}
