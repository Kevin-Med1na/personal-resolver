package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "consulta_documento")
public class ConsultaDocumentoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID uuid;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(
                    name = "documentType",
                    column = @Column(
                            name = "document_type",
                            nullable = false,
                            length = 20
                    )
            ),
            @AttributeOverride(
                    name = "documentNumber",
                    column = @Column(
                            name = "document_number",
                            nullable = false,
                            length = 50
                    )
            )
    })
    private DocumentId documentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "raw_json_response",
            columnDefinition = "jsonb",
            nullable = false
    )
    private String externalJsonResponse;

    @Column(
            name = "response_hash",
            nullable = false,
            length = 64
    )
    private String responseHash;

    @Column(
            name = "first_consult_date",
            nullable = false
    )
    private LocalDateTime primeraFechaConsulta;

    @Column(
            name = "last_consult_date",
            nullable = false
    )
    private LocalDateTime lastFechaConsulta;

    @Column(
            name = "consult_count",
            nullable = false
    )
    private Integer consultCount;
}