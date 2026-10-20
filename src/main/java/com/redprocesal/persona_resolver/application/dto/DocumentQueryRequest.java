package com.redprocesal.persona_resolver.application.dto;

import com.redprocesal.persona_resolver.domain.model.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DocumentQueryRequest(
        @NotNull(message = "documentType is required")
        DocumentType documentType,

        @NotBlank(message = "documentNumber is required")
        String documentNumber
) {
    public DocumentQueryRequest {
        if (documentNumber != null) {
            documentNumber = documentNumber.trim();
        }
    }
}