package com.redprocesal.persona_resolver.domain.port;

import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;

public interface CompanyDataSource {
    CompanyBasicData findByNit(String documentNumber);
}