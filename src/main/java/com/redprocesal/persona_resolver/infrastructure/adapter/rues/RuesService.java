package com.redprocesal.persona_resolver.infrastructure.adapter.rues;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;
import com.redprocesal.persona_resolver.domain.exception.ExternalSourceException;
import com.redprocesal.persona_resolver.domain.port.CompanyDataSource;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RuesService implements CompanyDataSource {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://app-antiguoprd.rues.org.co";
    private static final String SEARCH_PATH = "/RM";
    private static final String QUERY_ENDPOINT = "/Home/ConsultaNIT_json";

    private final String baseUrl;
    private final Duration timeout;
    private final String userAgent;

    public RuesService(
            @Value("${app.sources.rues.base-url:https://app-antiguoprd.rues.org.co}") String baseUrl,
            @Value("${app.scraper.timeout-ms:30000}") int timeoutMs,
            @Value("${app.sources.rues.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36}") String userAgent) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1000));
        this.userAgent = userAgent;
    }

    @Override
    public CompanyBasicData findByNit(String documentNumber) {
        String nit = normalizeNit(documentNumber);

        if (nit == null) {
            throw new ExternalSourceException(
                    "El NIT no es válido para consultar RUES.",
                    null);
        }

        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try {
            HttpResponse<String> landing = getSearchPage(client);

            if (landing.statusCode() < 200 || landing.statusCode() >= 400) {
                throw new ExternalSourceException(
                        "RUES no permitió iniciar la sesión HTTP. HTTP " + landing.statusCode(),
                        null);
            }

            String token = extractRequestVerificationToken(landing.body());

            if (token == null || token.isBlank()) {
                throw new ExternalSourceException(
                        "RUES no entregó __RequestVerificationToken en la página /RM.",
                        null);
            }

            String form = "__RequestVerificationToken=" + encode(token)
                    + "&txtNIT=" + encode(nit)
                    + "&txtCI="
                    + "&txtDV=";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + QUERY_ENDPOINT))
                    .timeout(timeout)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Origin", baseUrl)
                    .header("Referer", baseUrl + SEARCH_PATH)
                    .header("User-Agent", userAgent)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ExternalSourceException(
                        "ConsultaNIT_json respondió con HTTP " + response.statusCode(),
                        null);
            }

            return parseConsultaNitResponse(response.body(), nit);

        } catch (ExternalSourceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalSourceException(
                    "La consulta a RUES fue interrumpida.",
                    e);
        } catch (IOException | RuntimeException e) {
            throw new ExternalSourceException(
                    "No se pudo consultar directamente RUES: " + e.getMessage(),
                    e);
        }
    }

    public CompanyBasicData fetchByNit(String documentNumber) {
        return findByNit(documentNumber);
    }

    private HttpResponse<String> getSearchPage(HttpClient client) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + SEARCH_PATH))
                .timeout(timeout)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String extractRequestVerificationToken(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        Pattern inputPattern = Pattern.compile(
                "(?is)<input\\b[^>]*name\\s*=\\s*(['\"])__RequestVerificationToken\\1[^>]*>");

        Matcher inputMatcher = inputPattern.matcher(html);

        while (inputMatcher.find()) {
            String inputTag = inputMatcher.group();
            Pattern valuePattern = Pattern.compile("(?is)value\\s*=\\s*(['\"])(.*?)\\1");
            Matcher valueMatcher = valuePattern.matcher(inputTag);

            if (valueMatcher.find()) {
                String token = valueMatcher.group(2);
                if (token != null && !token.isBlank()) {
                    return token.trim();
                }
            }
        }

        Pattern fallback = Pattern.compile(
                "(?is)<input\\b(?=[^>]*name\\s*=\\s*(['\"])__RequestVerificationToken\\1)(?=[^>]*value\\s*=\\s*(['\"])(.*?)\\2)[^>]*>");

        Matcher matcher = fallback.matcher(html);
        if (matcher.find()) {
            return matcher.group(3).trim();
        }

        return null;
    }

    static CompanyBasicData parseConsultaNitResponse(String body, String documentNumber) {

        if (body == null || body.isBlank()) {
            throw new ExternalSourceException(
                    "RUES respondió vacío.",
                    null);
        }

        String normalizedNit = normalizeNit(documentNumber);

        if (normalizedNit == null) {
            throw new ExternalSourceException(
                    "El NIT no es válido para procesar la respuesta de RUES.",
                    null);
        }

        String cleanedBody = body.replace("\uFEFF", "").trim();

        try {
            JsonNode root = OBJECT_MAPPER.readTree(cleanedBody);

            if (root == null || !root.isObject()) {
                throw new ExternalSourceException(
                        "RUES respondió algo que no es un objeto JSON.",
                        null);
            }

            String errorCode = text(root, "codigo_error");
            String errorMessage = text(root, "mensaje_error");

            if (errorCode != null && !errorCode.isBlank() && !"0000".equals(errorCode)) {
                throw new ExternalSourceException(
                        "RUES respondió con codigo_error=" + errorCode
                                + (errorMessage == null || errorMessage.isBlank() ? "" : " - " + errorMessage),
                        null);
            }

            int records = root.path("records").asInt(0);
            if (records <= 0) {
                return null;
            }

            JsonNode rows = root.path("rows");

            if (!rows.isArray() || rows.size() == 0) {
                throw new ExternalSourceException(
                        "RUES indicó que existen registros, pero no devolvió filas válidas.",
                        null);
            }

            JsonNode row = rows.get(0);

            if (row == null || !row.isObject()) {
                throw new ExternalSourceException(
                        "RUES devolvió una fila inválida.",
                        null);
            }

            String reason = text(row, "razon_social");

            if (reason == null || reason.isBlank()) {
                throw new ExternalSourceException(
                        "RUES devolvió un registro sin razón social.",
                        null);
            }

            String municipality = text(row, "municipio");
            String city = extractCity(municipality);

            return new CompanyBasicData(
                    normalizedNit,
                    reason,
                    null,
                    text(row, "categoria_matricula"),
                    text(row, "estadoRM"),
                    city,
                    null);

        } catch (ExternalSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalSourceException(
                    "ConsultaNIT_json respondió correctamente, pero el contenido no pudo interpretarse como JSON.",
                    e);
        }
    }

    private static String normalizeNit(String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) {
            return null;
        }

        String cleaned = documentNumber
                .trim()
                .replace(".", "")
                .replace(" ", "");

        if (cleaned.contains("-")) {
            cleaned = cleaned.substring(0, cleaned.indexOf('-'));
        }

        cleaned = cleaned.replaceAll("\\D", "");

        if (cleaned.length() < 3 || cleaned.length() > 15) {
            return null;
        }

        return cleaned;
    }

    private static String extractCity(String municipality) {
        if (municipality == null || municipality.isBlank()) {
            return null;
        }

        int separator = municipality.indexOf('/');
        if (separator < 0) {
            return municipality.trim();
        }

        return municipality.substring(0, separator).trim();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }

        String value = node.asText();
        return value == null ? null : value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeBaseUrl(String value) {
        String result = (value == null || value.isBlank()) ? DEFAULT_BASE_URL : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}