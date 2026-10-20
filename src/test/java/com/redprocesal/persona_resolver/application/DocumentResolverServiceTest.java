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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;

import java.time.OffsetDateTime;

@ExtendWith(MockitoExtension.class)
class DocumentResolverServiceTest {

        @Mock
        private PersonDataSource personDataSource;

        @Mock
        private CompanyDataSource companyDataSource;

        @Mock
        private DocumentValidator documentValidator;

        @Mock
        private DocumentPersistenceAdapter documentPersistenceAdapter;

        @InjectMocks
        private DocumentResolverApplicationService service;

        @Test
        void resolve_Cedula_Success() {

                DocumentQueryRequest request = new DocumentQueryRequest(
                                DocumentType.CEDULA,
                                "12345678");

                PersonBasicData person = new PersonBasicData(
                                "12345678",
                                "LAURA SOFIA GOMEZ TORRES",
                                "LAURA SOFIA",
                                "GOMEZ TORRES",
                                "Femenino",
                                "BOLÍVAR",
                                "TURBACO",
                                null,
                                43,
                                "C",
                                "C01",
                                "Solo ingreso estimado",
                                "13836");

                doNothing()
                                .when(documentValidator)
                                .validate(any(DocumentQuery.class));

                when(personDataSource.findByCedula("12345678"))
                                .thenReturn(person);

                when(documentPersistenceAdapter.processAndSaveConsultation(any(DocumentQueryResponse.class)))
                                .thenReturn(
                                                new ConsultationMetadata(
                                                                1,
                                                                OffsetDateTime.now(),
                                                                null));

                DocumentQueryResponse response = service.resolve(request);

                assertNotNull(response);
                assertNotNull(response.consultCount());
                assertNotNull(response.firstConsultDate());
                assertEquals(1, response.consultCount());

                verify(documentValidator)
                                .validate(any(DocumentQuery.class));

                verify(personDataSource)
                                .findByCedula("12345678");

                verify(documentPersistenceAdapter)
                                .processAndSaveConsultation(
                                                argThat(requestResponse -> requestResponse.documentType()
                                                                .equals("CEDULA")
                                                                && requestResponse.documentNumber().equals("12345678")
                                                                && requestResponse.consultCount() == null
                                                                && requestResponse.firstConsultDate() == null
                                                                && requestResponse.previousConsultDate() == null));
        }

        @Test
        void resolve_Nit_Success() {

                DocumentQueryRequest request = new DocumentQueryRequest(
                                DocumentType.NIT,
                                "891408584-5");

                CompanyBasicData company = new CompanyBasicData(
                                "891408584",
                                "FRISBY S.A. BIC",
                                null,
                                "SOCIEDAD ó PERSONA JURIDICA PRINCIPAL ó ESAL",
                                "ACTIVA",
                                "PEREIRA",
                                null);

                doNothing()
                                .when(documentValidator)
                                .validate(any(DocumentQuery.class));

                when(companyDataSource.findByNit("891408584-5"))
                                .thenReturn(company);

                when(documentPersistenceAdapter.processAndSaveConsultation(any(DocumentQueryResponse.class)))
                                .thenReturn(
                                                new ConsultationMetadata(
                                                                1,
                                                                OffsetDateTime.now(),
                                                                null));

                DocumentQueryResponse response = service.resolve(request);

                assertNotNull(response);
                assertNotNull(response.consultCount());
                assertNotNull(response.firstConsultDate());
                assertEquals(1, response.consultCount());

                verify(companyDataSource)
                                .findByNit("891408584-5");

                verify(documentPersistenceAdapter)
        .processAndSaveConsultation(
                argThat(requestResponse ->
                        requestResponse.documentType().equals("NIT")
                                && requestResponse.documentNumber().equals("891408584")
                                && requestResponse.consultCount() == null
                                && requestResponse.firstConsultDate() == null
                                                                && requestResponse.previousConsultDate() == null
                )
        );
        }

        @Test
        void resolve_Cedula_NotFound() {

                DocumentQueryRequest request = new DocumentQueryRequest(
                                DocumentType.CEDULA,
                                "12345678");

                doNothing()
                                .when(documentValidator)
                                .validate(any(DocumentQuery.class));

                when(personDataSource.findByCedula("12345678"))
                                .thenReturn(null);

                assertThrows(
                                DocumentNotFoundException.class,
                                () -> service.resolve(request));

                verifyNoInteractions(documentPersistenceAdapter);
        }

        @Test
        void resolve_Nit_NotFound() {

                DocumentQueryRequest request = new DocumentQueryRequest(
                                DocumentType.NIT,
                                "891408584-5");

                doNothing()
                                .when(documentValidator)
                                .validate(any(DocumentQuery.class));

                when(companyDataSource.findByNit("891408584-5"))
                                .thenReturn(null);

                assertThrows(
                                DocumentNotFoundException.class,
                                () -> service.resolve(request));

                verifyNoInteractions(documentPersistenceAdapter);
        }
}