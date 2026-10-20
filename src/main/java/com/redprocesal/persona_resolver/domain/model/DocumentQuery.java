package com.redprocesal.persona_resolver.domain.model;

public record DocumentQuery(DocumentType documentType, String documentNumber) {

    /**
     * El número tal como queda en el historial: la cédula sin espacios
     * alrededor; el NIT sin puntos, espacios ni dígito de verificación.
     */
    public String canonicalNumber() {
        if (documentNumber == null) {
            return null;
        }

        String trimmed = documentNumber.trim();

        if (documentType != DocumentType.NIT) {
            return trimmed;
        }

        int checkDigit = trimmed.indexOf('-');
        String withoutCheckDigit = checkDigit >= 0 ? trimmed.substring(0, checkDigit) : trimmed;

        return withoutCheckDigit.replaceAll("\\D", "");
    }
}
