package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class DocumentId implements Serializable {
    private String documentType;
    private String documentNumber;
}

