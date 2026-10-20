package com.redprocesal.persona_resolver.infrastructure.adapter.rui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.domain.exception.ExternalSourceException;
import com.redprocesal.persona_resolver.domain.exception.SourceUnavailableException;
import com.redprocesal.persona_resolver.domain.port.PersonDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RuiService implements PersonDataSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuiService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://ventanillasocial.dnp.gov.co";

    /**
     * Tope por intento mientras se busca un nodo que resuelva. Es mucho mas corto
     * que el timeout general: durante el sondeo interesa descartar rapido un nodo
     * lento, no esperarlo.
     */
    private static final Duration PROBE_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Desde octubre de 2026 la landing incrusta un nonce por sesion
     * ({@code var _ruiPubNonce='...';}) y el portal lo envia en la cabecera
     * {@code X-Rui-Nonce}. Sin el, ObtenerDatosRUI responde ok:false a
     * cualquier cedula, lo mismo que si no existiera.
     */
    private static final Pattern NONCE_PATTERN =
            Pattern.compile("_ruiPubNonce\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final String NONCE_HEADER = "X-Rui-Nonce";

    private final String baseUrl;
    private final String endpointPath;
    private final Duration timeout;
    private final String userAgent;
    private final int maxAttempts;
    private final Duration sessionTtl;
    private final Duration probeBudget;
    private final Duration probeTimeout;

    /**
     * Última sesión que demostró servir consultas. Se reutiliza para quedarse en
     * el mismo nodo del balanceador. Ver {@link #findByCedula}.
     */
    private volatile CachedSession cachedSession;

    public RuiService(
            @Value("${app.sources.rui.base-url:https://ventanillasocial.dnp.gov.co}") String baseUrl,
            @Value("${app.sources.rui.endpoint-path:/Home/ObtenerDatosRUI}") String endpointPath,
            @Value("${app.scraper.timeout-ms:30000}") int timeoutMs,
            @Value("${app.sources.rui.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36}") String userAgent,
            @Value("${app.sources.rui.max-attempts:8}") int maxAttempts,
            @Value("${app.sources.rui.session-ttl-ms:600000}") long sessionTtlMs,
            @Value("${app.sources.rui.probe-budget-ms:12000}") long probeBudgetMs) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.endpointPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1000));
        this.userAgent = userAgent;
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.sessionTtl = Duration.ofMillis(Math.max(sessionTtlMs, 0));

        /*
         * El sondeo de nodos tiene que caber dentro del timeout de quien llama:
         * el proxy que tenga delante corta en algun momento, y quien esta frente
         * al formulario espera mucho menos. Por eso se acota tanto el tiempo
         * total del sondeo como el de cada intento, en vez de confiar solo en el
         * numero de intentos.
         */
        this.probeBudget = Duration.ofMillis(Math.max(probeBudgetMs, 1000));
        this.probeTimeout = this.timeout.compareTo(PROBE_ATTEMPT_TIMEOUT) < 0
                ? this.timeout
                : PROBE_ATTEMPT_TIMEOUT;
    }

    @Override
    public PersonBasicData findByCedula(String documentNumber) {
        String normalizedDocument = normalizeDocumentNumber(documentNumber);
        if (normalizedDocument == null) {
            throw new ExternalSourceException("El número de cédula no es válido para consultar RUI.", null);
        }

        try {
            /*
             * Ventanilla Social esta detras de un balanceador KEMP y solo algunos
             * de sus nodos resuelven consultas RUI. Los demas contestan
             * {"ok":false}, exactamente igual que una cedula que no existe, de
             * modo que la respuesta sola no permite distinguir ambos casos.
             *
             * La cookie KEMP_STICKY fija el nodo asignado. Por eso se reutiliza
             * una sesion que ya demostro servir, en vez de abrir una nueva en cada
             * consulta y volver a rifar el nodo: eso daba un acierto de ~1 en 4.
             */
            CachedSession cached = cachedSession;

            if (cached != null && !cached.isExpired(sessionTtl)) {
                PersonBasicData person =
                        queryRui(cached.session(), normalizedDocument, timeout);

                if (person != null) {
                    return person;
                }

                /*
                 * Ambiguo: o la cedula no existe, o el nodo dejo de servir. Se
                 * descarta la sesion y se resuelve con sesiones nuevas, que es la
                 * unica forma de distinguirlo.
                 */
                LOGGER.debug("La sesión RUI reutilizada respondió ok:false; se descarta.");
                cachedSession = null;
            }

            Instant deadline = Instant.now().plus(probeBudget);

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {

                /*
                 * El primer intento se hace siempre; a partir del segundo se
                 * respeta el presupuesto, para no exceder el timeout de quien
                 * llama y acabar en un 503 evitable.
                 */
                if (attempt > 1 && Instant.now().isAfter(deadline)) {
                    LOGGER.warn("Presupuesto de sondeo de RUI agotado tras {} intentos "
                            + "sin dar con un nodo que resuelva.", attempt - 1);

                    throw new SourceUnavailableException(
                            "RUI no resolvió la consulta dentro del tiempo disponible: "
                                    + "ninguno de los nodos probados respondió con información.",
                            null);
                }

                RuiSession session = openSession(probeTimeout);
                PersonBasicData person = queryRui(session, normalizedDocument, probeTimeout);

                if (person != null) {
                    cachedSession = new CachedSession(session, Instant.now());
                    LOGGER.debug("RUI resolvió la consulta en el intento {} de {}.",
                            attempt, maxAttempts);
                    return person;
                }
            }

            /*
             * Se agotaron los intentos, no el tiempo: varios nodos distintos
             * coincidieron en que no hay informacion, asi que lo mas probable es
             * que la cedula realmente no exista.
             */
            LOGGER.info("RUI respondió ok:false en {} intentos con nodos distintos; "
                    + "se reporta como no encontrado.", maxAttempts);
            return null;

        } catch (ExternalSourceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalSourceException("La consulta a RUI fue interrumpida.", e);
        } catch (IOException | RuntimeException e) {
            throw new ExternalSourceException(
                    "No se pudo consultar directamente la API ObtenerDatosRUI: " + e.getMessage(),
                    e);
        }
    }

    public PersonBasicData fetchByCedula(String documentNumber) {
        return findByCedula(documentNumber);
    }

    /**
     * Abre una sesion nueva: cliente con su propio almacen de cookies y visita a
     * la landing, que es la que entrega KEMP_STICKY, asigna el nodo y trae el
     * nonce de la sesion.
     */
    private RuiSession openSession(Duration attemptTimeout) throws IOException, InterruptedException {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(attemptTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpResponse<String> landing = getLandingPage(client, attemptTimeout);

        if (landing.statusCode() < 200 || landing.statusCode() >= 400) {
            throw new ExternalSourceException(
                    "Ventanilla Social no permitió iniciar la sesión HTTP (HTTP "
                            + landing.statusCode() + ").",
                    null);
        }

        /*
         * Sin nonce la consulta responderia ok:false y acabaria en un 404 falso.
         * Es un cambio de contrato del portal, asi que se informa como tal.
         */
        String nonce = extractNonce(landing.body());
        if (nonce == null) {
            throw new ExternalSourceException(
                    "Ventanilla Social cambió: la página ya no trae _ruiPubNonce, "
                            + "que la consulta RUI necesita en la cabecera " + NONCE_HEADER + ".",
                    null);
        }

        return new RuiSession(client, nonce);
    }

    static String extractNonce(String html) {
        if (html == null) {
            return null;
        }
        Matcher matcher = NONCE_PATTERN.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * @return los datos de la persona, o {@code null} si RUI respondió
     *         {@code ok:false} — que puede significar tanto "no existe" como
     *         "este nodo no resuelve consultas".
     */
    private PersonBasicData queryRui(RuiSession session, String document, Duration attemptTimeout)
            throws IOException, InterruptedException {

        String form = "pNumDoc=" + encode(document)
                + "&pTipDoc=" + encode("3");

        // Las mismas cabeceras que envia el XMLHttpRequest del portal.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpointPath))
                .timeout(attemptTimeout)
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", baseUrl)
                .header("Referer", baseUrl + "/")
                .header("User-Agent", userAgent)
                .header(NONCE_HEADER, session.nonce())
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = session.client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        LOGGER.debug("RUI ObtenerDatosRUI -> HTTP {}", response.statusCode());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ExternalSourceException(
                    "La API ObtenerDatosRUI respondió con HTTP " + response.statusCode() + ".",
                    null);
        }

        return parseRuiResponse(response.body(), document);
    }

    /** Cliente con las cookies de la sesion y el nonce que le entrego la landing. */
    private record RuiSession(HttpClient client, String nonce) {
    }

    private record CachedSession(RuiSession session, Instant validatedAt) {

        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(validatedAt.plus(ttl));
        }
    }

    private HttpResponse<String> getLandingPage(HttpClient client, Duration attemptTimeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .timeout(attemptTimeout)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static PersonBasicData parseRuiResponse(String body, String documentNumber) {
        if (body == null || body.isBlank()) {
            throw new ExternalSourceException(
                    "La API ObtenerDatosRUI respondió vacía.",
                    null);
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);

            if (!root.path("ok").asBoolean(false)) {
                return null;
            }

            String fullName = text(root, "nombre");
            if (fullName == null || fullName.isBlank()) {
                throw new ExternalSourceException(
                        "La API ObtenerDatosRUI indicó una respuesta válida, pero no contiene el nombre de la persona.",
                        null);
            }

            NameParts parts = splitName(fullName);

            return new PersonBasicData(
                    documentNumber,
                    fullName,
                    parts.firstName(),
                    parts.lastName(),
                    text(root, "sexo"),
                    text(root, "departamento"),
                    text(root, "municipio"),
                    null,
                    integer(root, "edad"),
                    // grupRui, nivelRui y grupoIngresos se ignoran a propósito:
                    // son la clasificación socioeconómica de la persona.
                    text(root, "codMpio"));
        } catch (Exception e) {
            throw new ExternalSourceException(
                    "La API ObtenerDatosRUI respondió, pero el JSON no tiene el formato esperado.",
                    e);
        }
    }

    static String normalizeDocumentNumber(String documentNumber) {
        if (documentNumber == null) {
            return null;
        }
        String normalized = documentNumber.trim();
        if (!normalized.matches("\\d{6,12}")) {
            return null;
        }
        return normalized;
    }

    private static String normalizeBaseUrl(String value) {
        String result = (value == null || value.isBlank()) ? DEFAULT_BASE_URL : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null ? null : value.trim();
    }

    private static Integer integer(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static NameParts splitName(String fullName) {
        String normalized = fullName.trim().replaceAll("\\s+", " ");
        String[] tokens = normalized.split(" ");

        if (tokens.length == 1) {
            return new NameParts(tokens[0], null);
        }
        if (tokens.length == 2) {
            return new NameParts(tokens[0], tokens[1]);
        }

        String firstName = tokens[0] + " " + tokens[1];
        StringBuilder lastName = new StringBuilder();
        for (int i = 2; i < tokens.length; i++) {
            if (lastName.length() > 0) {
                lastName.append(' ');
            }
            lastName.append(tokens[i]);
        }
        return new NameParts(firstName, lastName.toString());
    }

    private record NameParts(String firstName, String lastName) {
    }
}
