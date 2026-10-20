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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "empresa",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_empresa_document",
                        columnNames = {
                                "document_type",
                                "document_number"
                        }
                )
        }
)
public class EmpresaEntity {

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

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "business_type")
    private String businessType;

    private String state;

    private String city;

    @Column(name = "fecha_last_consult")
    private LocalDateTime fechaLastConsult;
}