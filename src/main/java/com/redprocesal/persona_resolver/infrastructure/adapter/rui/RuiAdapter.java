package com.redprocesal.persona_resolver.infrastructure.adapter.rui;

import com.redprocesal.persona_resolver.domain.port.PersonDataSource;
import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Consulta RUI directamente. Ventanilla Social bloquea IPs extranjeras, así
 * que solo funciona si el backend sale a internet con una IP colombiana.
 *
 * {@link Primary} porque {@link RuiService} también implementa
 * {@link PersonDataSource}.
 */
@Component
@Primary
public class RuiAdapter implements PersonDataSource {

    private final RuiService ruiService;

    public RuiAdapter(RuiService ruiService) {
        this.ruiService = ruiService;
    }

    @Override
    public PersonBasicData findByCedula(String documentNumber) {
        return ruiService.fetchByCedula(documentNumber);
    }
}
