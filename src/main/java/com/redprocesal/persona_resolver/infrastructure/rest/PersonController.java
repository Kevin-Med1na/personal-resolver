package com.redprocesal.persona_resolver.infrastructure.rest;

import com.redprocesal.persona_resolver.application.dto.DocumentQueryRequest;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;
import com.redprocesal.persona_resolver.application.ResolveDocumentUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PersonController {

    private final ResolveDocumentUseCase resolveDocumentUseCase;

    public PersonController(ResolveDocumentUseCase resolveDocumentUseCase) {
        this.resolveDocumentUseCase = resolveDocumentUseCase;
    }

    @PostMapping("/lookup")
    public ResponseEntity<DocumentQueryResponse> query(
            @Valid @RequestBody DocumentQueryRequest request) {
        return ResponseEntity.ok(resolveDocumentUseCase.resolve(request));
    }
}
