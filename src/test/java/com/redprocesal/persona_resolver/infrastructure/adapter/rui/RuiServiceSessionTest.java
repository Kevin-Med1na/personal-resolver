package com.redprocesal.persona_resolver.infrastructure.adapter.rui;

import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.domain.exception.ExternalSourceException;
import com.redprocesal.persona_resolver.domain.exception.SourceUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ventanilla Social está detrás de un balanceador KEMP en el que solo algunos
 * nodos resuelven consultas; el resto responde {@code ok:false}, igual que una
 * cédula inexistente. Estas pruebas simulan ese comportamiento con un servidor
 * local para verificar que el servicio reintenta con sesiones nuevas y después
 * se queda en la que funcionó.
 *
 * Como el portal real, la landing entrega un nonce por sesión y la consulta
 * solo resuelve si llega con el nonce de esa misma sesión en X-Rui-Nonce.
 */
class RuiServiceSessionTest {

    private static final String FOUND = """
            {"ok":true,"nombre":"CARLOS ANDRES RUIZ DIAZ","sexo":"Masculino",
             "departamento":"BOLÍVAR","municipio":"CARTAGENA DE INDIAS","edad":28,
             "grupRui":"D","nivelRui":"D10","grupoIngresos":"Ingreso observado",
             "codMpio":"13001"}
            """;

    private static final String NOT_FOUND = "{\"ok\":false}";

    private HttpServer server;
    private final AtomicInteger landingHits = new AtomicInteger();
    private final AtomicInteger lookupHits = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldRetryWithNewSessionsUntilANodeResolves() throws IOException {

        // Los dos primeros nodos no sirven; el tercero sí.
        String baseUrl = startServer(hit -> hit >= 3 ? FOUND : NOT_FOUND);

        PersonBasicData person = serviceFor(baseUrl, 8).findByCedula("1234567890");

        assertNotNull(person);
        assertEquals("CARLOS ANDRES RUIZ DIAZ", person.fullName());
        assertEquals(3, lookupHits.get(), "debió reintentar hasta dar con un nodo que resuelve");
        assertEquals(3, landingHits.get(), "cada reintento abre una sesión nueva");
    }

    @Test
    void shouldReuseTheSessionThatWorkedInsteadOfReopeningOnEveryLookup() throws IOException {

        String baseUrl = startServer(hit -> hit >= 2 ? FOUND : NOT_FOUND);

        RuiService service = serviceFor(baseUrl, 8);

        assertNotNull(service.findByCedula("1234567890"));
        int landingsAfterFirst = landingHits.get();

        assertNotNull(service.findByCedula("1234567890"));
        assertNotNull(service.findByCedula("1234567890"));

        assertEquals(landingsAfterFirst, landingHits.get(),
                "las consultas siguientes deben reutilizar la sesión validada, sin volver a la landing");
        assertEquals(4, lookupHits.get());
    }

    @Test
    void shouldReportNotFoundAfterExhaustingAttempts() throws IOException {

        String baseUrl = startServer(hit -> NOT_FOUND);

        assertNull(serviceFor(baseUrl, 4).findByCedula("1234567890"));

        assertEquals(4, lookupHits.get(), "debe agotar exactamente el presupuesto de intentos");
    }

    @Test
    void shouldNotRetryWhenTheSourceFailsWithAnHttpError() throws IOException {

        String baseUrl = startServerReturning(503);

        assertThrows(Exception.class, () -> serviceFor(baseUrl, 8).findByCedula("1234567890"));

        assertEquals(1, lookupHits.get(),
                "un error HTTP no es un nodo degradado: debe propagarse sin reintentar");
    }

    /**
     * Si el sondeo agota el tiempo en vez de los intentos, el resultado es
     * inconcluyente: no se puede afirmar que la cédula no exista. Debe salir un
     * error de disponibilidad (503) y no un "no encontrado" (404) falso.
     */
    @Test
    void shouldFailAsUnavailableWhenTheProbeBudgetRunsOutBeforeTheAttempts() throws IOException {

        String baseUrl = startSlowServer(400);

        assertThrows(SourceUnavailableException.class,
                () -> serviceFor(baseUrl, 50, 1_000L).findByCedula("1234567890"));

        assertTrue(lookupHits.get() < 50,
                "debió cortar por presupuesto de tiempo, no agotar los 50 intentos");
    }

    @Test
    void shouldSendTheNonceThatTheLandingGaveToTheSameSession() throws IOException {

        String baseUrl = startServer(hit -> FOUND);

        assertNotNull(serviceFor(baseUrl, 8).findByCedula("1234567890"),
                "sin el nonce de la sesión el portal responde ok:false y la cédula saldría como no encontrada");

        assertEquals(1, lookupHits.get());
    }

    /**
     * Si la página deja de traer el nonce, consultar igual daría ok:false en
     * los 8 intentos y un 404 falso. Tiene que salir como cambio de contrato.
     */
    @Test
    void shouldReportAContractChangeWhenTheLandingHasNoNonce() throws IOException {

        String baseUrl = startServerWithoutNonce();

        ExternalSourceException error = assertThrows(ExternalSourceException.class,
                () -> serviceFor(baseUrl, 8).findByCedula("1234567890"));

        assertFalse(error instanceof SourceUnavailableException);
        assertTrue(error.getMessage().contains("_ruiPubNonce"));
        assertEquals(0, lookupHits.get(), "sin nonce no tiene sentido consultar");
    }

    @Test
    void shouldExtractTheNonceAsThePortalWritesIt() {

        String html = "function _e(s){return s} var _ruiPubNonce='0123456789abcdef0123456789abcdef';"
                + " function ConsultarRUI(){ var tipDoc=document.getElementById('ruiTipoDoc').value; }";

        assertEquals("0123456789abcdef0123456789abcdef", RuiService.extractNonce(html));
        assertNull(RuiService.extractNonce("<html></html>"));
        assertNull(RuiService.extractNonce(null));
    }

    private RuiService serviceFor(String baseUrl, int maxAttempts) {
        return serviceFor(baseUrl, maxAttempts, 600_000L);
    }

    private RuiService serviceFor(String baseUrl, int maxAttempts, long probeBudgetMs) {
        return new RuiService(baseUrl, "/Home/ObtenerDatosRUI", 5000, "test-agent",
                maxAttempts, 600_000L, probeBudgetMs);
    }

    /** RUI simulado que nunca resuelve y además tarda en responder. */
    private String startSlowServer(long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/Home/ObtenerDatosRUI", exchange -> {
            exchange.getRequestBody().readAllBytes();
            lookupHits.incrementAndGet();
            sleep(delayMs);
            send(exchange, 200, NOT_FOUND);
        });

        server.createContext("/", this::landing);

        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @param bodyForHit recibe el número de consulta (1, 2, 3...) y decide el
     *                   cuerpo con el que responde el RUI simulado.
     */
    private String startServer(java.util.function.IntFunction<String> bodyForHit) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/Home/ObtenerDatosRUI", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int hit = lookupHits.incrementAndGet();
            send(exchange, 200, hasSessionNonce(exchange) ? bodyForHit.apply(hit) : NOT_FOUND);
        });

        server.createContext("/", this::landing);

        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startServerReturning(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/Home/ObtenerDatosRUI", exchange -> {
            exchange.getRequestBody().readAllBytes();
            lookupHits.incrementAndGet();
            send(exchange, status, "{}");
        });

        server.createContext("/", this::landing);

        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Landing como la del portal: abre sesión con una cookie y trae su nonce. */
    private void landing(HttpExchange exchange) throws IOException {
        int session = landingHits.incrementAndGet();
        exchange.getResponseHeaders().add("Set-Cookie", "ASP.NET_SessionId=" + session + "; Path=/");
        send(exchange, 200, "<script>var _ruiPubNonce='nonce-" + session + "';</script>");
    }

    private static boolean hasSessionNonce(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String nonce = exchange.getRequestHeaders().getFirst("X-Rui-Nonce");
        if (cookie == null || nonce == null) {
            return false;
        }
        java.util.regex.Matcher session =
                java.util.regex.Pattern.compile("ASP\\.NET_SessionId=(\\d+)").matcher(cookie);
        return session.find() && nonce.equals("nonce-" + session.group(1));
    }

    private String startServerWithoutNonce() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/Home/ObtenerDatosRUI", exchange -> {
            exchange.getRequestBody().readAllBytes();
            lookupHits.incrementAndGet();
            send(exchange, 200, NOT_FOUND);
        });

        server.createContext("/", exchange -> {
            landingHits.incrementAndGet();
            send(exchange, 200, "<html></html>");
        });

        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(HttpExchange exchange, int status, String body)
            throws IOException {

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }

        exchange.close();
    }
}
