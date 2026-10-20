package com.redprocesal.persona_resolver.infrastructure.adapter.persistence;

import com.redprocesal.persona_resolver.domain.model.DocumentType;
import com.redprocesal.persona_resolver.domain.port.ExcludedDocuments;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.DocumentoExcluidoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExcludedDocumentsAdapter implements ExcludedDocuments {

    private final DocumentoExcluidoRepository documentoExcluidoRepository;

    @Override
    public boolean contains(DocumentType type, String documentNumber) {
        return documentoExcluidoRepository.existsById(new DocumentId(type.name(), documentNumber));
    }
}
