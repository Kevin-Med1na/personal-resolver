package com.redprocesal.persona_resolver.infrastructure.adapter.rues;

import com.redprocesal.persona_resolver.domain.port.CompanyDataSource;
import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RuesAdapter implements CompanyDataSource {

    private final RuesService ruesService;

    public RuesAdapter(RuesService ruesService) {
        this.ruesService = ruesService;
    }

    @Override
    public CompanyBasicData findByNit(String documentNumber) {
        return ruesService.fetchByNit(documentNumber);
    }
}