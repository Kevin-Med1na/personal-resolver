package com.redprocesal.persona_resolver.application.dto;

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
        String ruiGroup,
        String ruiLevel,
        String incomeGroup,
        String municipalityCode
) {
}
