package com.redprocesal.persona_resolver.application.dto;

/**
 * Datos de una persona que el servicio devuelve y guarda. La fuente también
 * entrega la clasificación socioeconómica (grupo y nivel RUI, grupo de
 * ingresos); no se lee, no se guarda y no se devuelve.
 */
public record PersonBasicData(
        String documentNumber,
        String fullName,
        String firstName,
        String lastName,
        String gender,
        String state,
        String city,
        String address,
        Integer age,
        String municipalityCode
) {
}
