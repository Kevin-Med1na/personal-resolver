package com.redprocesal.persona_resolver.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentQueryTest {

    @Test
    void cedulaKeepsItsDigits() {
        assertEquals("12345678", new DocumentQuery(DocumentType.CEDULA, " 12345678 ").canonicalNumber());
    }

    @Test
    void nitDropsDotsSpacesAndCheckDigit() {
        assertEquals("891408584", new DocumentQuery(DocumentType.NIT, "891.408.584-5").canonicalNumber());
        assertEquals("891408584", new DocumentQuery(DocumentType.NIT, "891 408 584").canonicalNumber());
        assertEquals("891408584", new DocumentQuery(DocumentType.NIT, "891408584").canonicalNumber());
    }

    @Test
    void missingNumberStaysMissing() {
        assertNull(new DocumentQuery(DocumentType.NIT, null).canonicalNumber());
    }
}
