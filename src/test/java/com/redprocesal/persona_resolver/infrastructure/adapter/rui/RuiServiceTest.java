package com.redprocesal.persona_resolver.infrastructure.adapter.rui;

import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.domain.exception.ExternalSourceException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuiServiceTest {

    @Test
    void shouldParseRealRuiResponse() {
        String json = "{"
                + "\"ok\":true,"
                + "\"grupoIngresos\":\"Solo ingreso estimado\","
                + "\"grupRui\":\"C\","
                + "\"nivelRui\":\"C01\","
                + "\"municipio\":\"TURBACO\","
                + "\"departamento\":\"BOLÍVAR\","
                + "\"codMpio\":\"13836\","
                + "\"nombre\":\"JUAN CARLOS PEREZ GOMEZ\","
                + "\"sexo\":\"Masculino\","
                + "\"edad\":20"
                + "}";

        PersonBasicData person = RuiService.parseRuiResponse(json, "9876543210");

        assertNotNull(person);
        assertEquals("9876543210", person.documentNumber());
        assertEquals("JUAN CARLOS PEREZ GOMEZ", person.fullName());
        assertEquals("JUAN CARLOS", person.firstName());
        assertEquals("PEREZ GOMEZ", person.lastName());
        assertEquals("Masculino", person.gender());
        assertEquals("TURBACO", person.city());
        assertEquals("BOLÍVAR", person.state());
        assertEquals(20, person.age());
        assertEquals("C", person.ruiGroup());
        assertEquals("C01", person.ruiLevel());
        assertEquals("Solo ingreso estimado", person.incomeGroup());
        assertEquals("13836", person.municipalityCode());
    }

    @Test
    void shouldReturnNullWhenRuiDoesNotFindPerson() {
        assertNull(RuiService.parseRuiResponse("{\"ok\":false}", "9876543210"));
    }

    @Test
    void shouldRejectInvalidCedula() {
        assertNull(RuiService.normalizeDocumentNumber(null));
        assertNull(RuiService.normalizeDocumentNumber("abc123"));
        assertNull(RuiService.normalizeDocumentNumber("12345"));
        assertNull(RuiService.normalizeDocumentNumber("1234567890123"));
        assertEquals("9876543210", RuiService.normalizeDocumentNumber(" 9876543210 "));
    }

    @Test
    void shouldReturnNullWhenRuiReportsNotFound() {

        String json = """
                {
                    "ok": false
                }
                """;

        PersonBasicData person = RuiService.parseRuiResponse(
                json,
                "12345678");

        assertNull(person);
    }

    @Test
    void shouldThrowErrorWhenRuiReturnsEmptyBody() {

        assertThrows(
                ExternalSourceException.class,
                () -> RuiService.parseRuiResponse(
                        "",
                        "12345678"));
    }

    @Test
    void shouldThrowErrorWhenRuiFoundButNameIsMissing() {

        String json = """
                {
                    "ok": true,
                    "sexo": "Femenino"
                }
                """;

        assertThrows(
                ExternalSourceException.class,
                () -> RuiService.parseRuiResponse(
                        json,
                        "12345678"));
    }
}
