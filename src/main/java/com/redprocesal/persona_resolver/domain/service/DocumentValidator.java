package com.redprocesal.persona_resolver.domain.service;

import com.redprocesal.persona_resolver.domain.exception.InvalidDocumentException;
import com.redprocesal.persona_resolver.domain.model.DocumentQuery;
import org.springframework.stereotype.Component;

@Component
public class DocumentValidator {

    public void validate(DocumentQuery query) {
        if (query == null) {
            throw new InvalidDocumentException("La consulta no puede ser nula");
        }

        if (query.documentType() == null) {
            throw new InvalidDocumentException("Tipo de documento no soportado");
        }

        if (query.documentNumber() == null || query.documentNumber().isBlank()) {
            throw new InvalidDocumentException(
                    "Número de documento no puede ser nulo o vacío");
        }

        switch (query.documentType()) {
            case CEDULA -> validateCedula(query.documentNumber());
            case NIT -> validateNit(query.documentNumber());
            default -> throw new InvalidDocumentException(
                    "Tipo de documento no soportado");
        }
    }

    private void validateCedula(String documentNumber) {
        String document = documentNumber.trim();

        if (!document.matches("\\d+")) {
            throw new InvalidDocumentException(
                    "El número de cédula debe contener solo dígitos");
        }

        if (document.length() < 6 || document.length() > 12) {
            throw new InvalidDocumentException(
                    "La cédula debe tener entre 6 y 12 dígitos");
        }
    }

    private void validateNit(String documentNumber) {
        String document = documentNumber.trim();

        if (!document.matches("[0-9.\\- ]+")) {
            throw new InvalidDocumentException(
                    "El NIT debe contener solo dígitos, puntos, espacios o guion");
        }

        String digitsOnly = document.replaceAll("\\D", "");

        if (digitsOnly.length() < 6 || digitsOnly.length() > 15) {
            throw new InvalidDocumentException(
                    "El NIT debe contener entre 6 y 15 dígitos");
        }
    }
}