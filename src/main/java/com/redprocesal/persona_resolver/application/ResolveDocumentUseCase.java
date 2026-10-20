package com.redprocesal.persona_resolver.application;

import com.redprocesal.persona_resolver.application.dto.DocumentQueryRequest;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;

public interface ResolveDocumentUseCase {
    DocumentQueryResponse resolve(DocumentQueryRequest request);
}