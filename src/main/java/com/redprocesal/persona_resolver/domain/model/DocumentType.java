package com.redprocesal.persona_resolver.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DocumentType {
    CEDULA,
    NIT;
    

    @JsonCreator
    public static DocumentType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        if ("NIT".equals(normalized) || "RM".equals(normalized)) {
            return NIT;
        }
        return switch (normalized) {
            case "CEDULA" -> CEDULA;
            case "NIT" -> NIT;
            default -> null;
        };
    }

    @JsonValue
    public String toValue() {
        return name();
    }
}
