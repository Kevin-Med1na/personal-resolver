package com.redprocesal.persona_resolver.domain.service;

import com.redprocesal.persona_resolver.domain.exception.InvalidDocumentException;
import com.redprocesal.persona_resolver.domain.model.DocumentQuery;
import com.redprocesal.persona_resolver.domain.model.DocumentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentValidatorTest {

    private final DocumentValidator validator =
            new DocumentValidator();

    @Test
    void shouldAcceptValidCedula() {
        assertDoesNotThrow(() ->
                validator.validate(
                        new DocumentQuery(
                                DocumentType.CEDULA,
                                "12345678"
                        )
                )
        );
    }

    @Test
    void shouldRejectCedulaWithLetters() {
        assertThrows(
                InvalidDocumentException.class,
                () -> validator.validate(
                        new DocumentQuery(
                                DocumentType.CEDULA,
                                "45546A97"
                        )
                )
        );
    }

    @Test
    void shouldRejectCedulaWithInvalidLength() {
        assertThrows(
                InvalidDocumentException.class,
                () -> validator.validate(
                        new DocumentQuery(
                                DocumentType.CEDULA,
                                "12345"
                        )
                )
        );
    }

    @Test
    void shouldAcceptNitWithoutVerificationDigit() {
        assertDoesNotThrow(() ->
                validator.validate(
                        new DocumentQuery(
                                DocumentType.NIT,
                                "891408584"
                        )
                )
        );
    }

    @Test
    void shouldAcceptNitWithVerificationDigit() {
        assertDoesNotThrow(() ->
                validator.validate(
                        new DocumentQuery(
                                DocumentType.NIT,
                                "891408584-5"
                        )
                )
        );
    }

    @Test
    void shouldAcceptFormattedNit() {
        assertDoesNotThrow(() ->
                validator.validate(
                        new DocumentQuery(
                                DocumentType.NIT,
                                "891.408.584-5"
                        )
                )
        );
    }

    @Test
    void shouldRejectNitWithLetters() {
        assertThrows(
                InvalidDocumentException.class,
                () -> validator.validate(
                        new DocumentQuery(
                                DocumentType.NIT,
                                "891ABC584"
                        )
                )
        );
    }

    @Test
    void shouldRejectNullQuery() {
        assertThrows(
                InvalidDocumentException.class,
                () -> validator.validate(null)
        );
    }

    @Test
    void shouldRejectNullDocumentType() {
        assertThrows(
                InvalidDocumentException.class,
                () -> validator.validate(
                        new DocumentQuery(
                                null,
                                "12345678"
                        )
                )
        );
    }
}