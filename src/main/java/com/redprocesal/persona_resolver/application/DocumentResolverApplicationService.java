package com.redprocesal.persona_resolver.application;

import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;
import com.redprocesal.persona_resolver.application.dto.ConsultationMetadata;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryRequest;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;
import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.domain.exception.DocumentNotFoundException;
import com.redprocesal.persona_resolver.domain.model.DocumentQuery;
import com.redprocesal.persona_resolver.domain.model.DocumentType;
import com.redprocesal.persona_resolver.domain.port.CompanyDataSource;
import com.redprocesal.persona_resolver.domain.port.PersonDataSource;
import com.redprocesal.persona_resolver.domain.service.DocumentValidator;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.DocumentPersistenceAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentResolverApplicationService implements ResolveDocumentUseCase {

    private final PersonDataSource personDataSource;
    private final CompanyDataSource companyDataSource;
    private final DocumentValidator documentValidator;
    private final DocumentPersistenceAdapter documentPersistenceAdapter;

    @Override
    public DocumentQueryResponse resolve(DocumentQueryRequest request) {

        DocumentQuery query = request == null
                ? null
                : new DocumentQuery(
                        request.documentType(),
                        request.documentNumber()
                );

        documentValidator.validate(query);

        DocumentQueryResponse response;

        if (query.documentType() == DocumentType.CEDULA) {

            PersonBasicData person =
                    personDataSource.findByCedula(
                            query.documentNumber()
                    );

            if (person == null) {
                throw new DocumentNotFoundException(
                        "No se encontró información para la cédula consultada."
                );
            }

            response = new DocumentQueryResponse(
                    DocumentType.CEDULA.name(),
                    person.documentNumber(),
                    "RUI",
                    "FOUND",
                    "PERSON",
                    person,
                    null,
                    "Consulta realizada con éxito"
            );

        } else {

            CompanyBasicData company =
                    companyDataSource.findByNit(
                            query.documentNumber()
                    );

            if (company == null) {
                throw new DocumentNotFoundException(
                        "No se encontró información para el NIT consultado."
                );
            }

            response = new DocumentQueryResponse(
                    DocumentType.NIT.name(),
                    company.documentNumber(),
                    "RUES",
                    "FOUND",
                    "COMPANY",
                    null,
                    company,
                    "Consulta realizada con éxito"
            );
        }

        ConsultationMetadata metadata =
                documentPersistenceAdapter
                        .processAndSaveConsultation(response);

        return response.withConsultationMetadata(metadata);
    }
}