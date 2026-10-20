package com.redprocesal.persona_resolver.application.dto;

public record CompanyBasicData(
        String documentNumber,
        String companyName,
        String legalRepresentative,
        String businessType,
        String state,
        String city,
        String address
) {

        
}
