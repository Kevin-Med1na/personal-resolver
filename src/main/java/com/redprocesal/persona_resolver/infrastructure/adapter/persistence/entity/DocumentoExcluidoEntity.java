package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Documento cuyo titular pidió la supresión de sus datos. Se agrega a mano
 * (ver "Solicitudes de supresión" en el README).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "documento_excluido")
public class DocumentoExcluidoEntity {

    @EmbeddedId
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

    @Column(name = "excluded_at")
    private LocalDateTime excludedAt;
}
