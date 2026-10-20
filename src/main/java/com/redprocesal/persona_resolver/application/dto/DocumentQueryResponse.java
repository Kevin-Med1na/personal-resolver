package com.redprocesal.persona_resolver.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentQueryResponse(
        String documentType,
        String documentNumber,
        String source,
        String status,
        String entityType,
        PersonBasicData person,
        CompanyBasicData company,
        String message,
        Integer consultCount,
        OffsetDateTime firstConsultDate,
        OffsetDateTime previousConsultDate
) {

    public DocumentQueryResponse(
            String documentType,
            String documentNumber,
            String source,
            String status,
            String entityType,
            PersonBasicData person,
            CompanyBasicData company,
            String message
    ) {
        this(
                documentType,
                documentNumber,
                source,
                status,
                entityType,
                person,
                company,
                message,
                null,
                null,
                null
        );
    }

    public DocumentQueryResponse withConsultationMetadata(
            ConsultationMetadata metadata
    ) {
        return new DocumentQueryResponse(
                documentType,
                documentNumber,
                source,
                status,
                entityType,
                person,
                company,
                message,
                metadata.consultCount(),
                metadata.firstConsultDate(),
                metadata.previousConsultDate()
        );
    }
}
