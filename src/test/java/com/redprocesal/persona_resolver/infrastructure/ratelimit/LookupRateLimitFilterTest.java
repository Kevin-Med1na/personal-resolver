package com.redprocesal.persona_resolver.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupRateLimitFilterTest {

    /** Reloj que la prueba adelanta a mano. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T15:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();

    private LookupRateLimitFilter filter(int perMinute, int perDay, int global) {
        return new LookupRateLimitFilter(perMinute, perDay, global, new ObjectMapper(), clock);
    }

    private MockHttpServletResponse lookup(LookupRateLimitFilter filter, String clientIp) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/lookup");
        request.addHeader("x-vercel-forwarded-for", clientIp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void shouldRejectAClientOverItsPerMinuteLimitAndLetItBackLater() throws Exception {
        LookupRateLimitFilter filter = filter(2, 100, 100);

        assertEquals(200, lookup(filter, "203.0.113.7").getStatus());
        assertEquals(200, lookup(filter, "203.0.113.7").getStatus());

        MockHttpServletResponse rejected = lookup(filter, "203.0.113.7");
        assertEquals(429, rejected.getStatus());
        assertEquals("60", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("Demasiadas consultas seguidas"),
                rejected.getContentAsString());

        // Otro cliente no se ve afectado.
        assertEquals(200, lookup(filter, "198.51.100.4").getStatus());

        clock.advance(Duration.ofSeconds(61));
        assertEquals(200, lookup(filter, "203.0.113.7").getStatus());
    }

    @Test
    void shouldApplyTheDailyLimitAcrossMinutes() throws Exception {
        LookupRateLimitFilter filter = filter(10, 3, 100);

        for (int i = 0; i < 3; i++) {
            assertEquals(200, lookup(filter, "203.0.113.7").getStatus());
            clock.advance(Duration.ofMinutes(5));
        }

        assertEquals(429, lookup(filter, "203.0.113.7").getStatus());
    }

    @Test
    void shouldApplyTheGlobalLimitToEveryone() throws Exception {
        LookupRateLimitFilter filter = filter(10, 100, 2);

        assertEquals(200, lookup(filter, "203.0.113.7").getStatus());
        assertEquals(200, lookup(filter, "198.51.100.4").getStatus());
        assertEquals(429, lookup(filter, "192.0.2.1").getStatus());
    }

    @Test
    void shouldUseTheFirstForwardedAddressWhenVercelHeadersAreMissing() throws Exception {
        LookupRateLimitFilter filter = filter(1, 100, 100);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/lookup");
        first.addHeader("x-forwarded-for", "203.0.113.7, 10.0.0.1");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/lookup");
        second.addHeader("x-forwarded-for", "203.0.113.7, 10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
    }

    @Test
    void shouldNotLimitTheHealthCheck() throws Exception {
        LookupRateLimitFilter filter = filter(1, 1, 1);

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/health"), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
            assertNull(response.getHeader("Retry-After"));
        }
    }

    @Test
    void zeroDisablesALimit() throws Exception {
        LookupRateLimitFilter filter = filter(0, 0, 0);

        for (int i = 0; i < 50; i++) {
            assertEquals(200, lookup(filter, "203.0.113.7").getStatus());
        }
    }
}
