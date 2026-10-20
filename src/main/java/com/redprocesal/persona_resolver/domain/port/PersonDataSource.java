package com.redprocesal.persona_resolver.domain.port;

import com.redprocesal.persona_resolver.application.dto.PersonBasicData;

public interface PersonDataSource {
    PersonBasicData findByCedula(String documentNumber);
}