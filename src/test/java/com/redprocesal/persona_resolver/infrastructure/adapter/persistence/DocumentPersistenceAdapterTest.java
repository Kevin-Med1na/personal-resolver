package com.redprocesal.persona_resolver.infrastructure.adapter.persistence;

import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;
import com.redprocesal.persona_resolver.application.dto.ConsultationMetadata;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;
import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.DocumentPersistenceAdapter;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.ConsultaDocumentoEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.PersonaEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.ConsultaDocumentoRepository;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.EmpresaRepository;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.PersonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentPersistenceAdapterTest {

        @Mock
        private ConsultaDocumentoRepository consultaDocumentoRepository;

        @Mock
        private PersonaRepository personaRepository;

        @Mock
        private EmpresaRepository empresaRepository;

        private DocumentPersistenceAdapter adapter;

        private final ObjectMapper objectMapper = new ObjectMapper();

        @BeforeEach
        void setUp() {
                adapter = new DocumentPersistenceAdapter(
                                consultaDocumentoRepository,
                                personaRepository,
                                empresaRepository,
                                objectMapper);
        }

        @Test
        void shouldCreateHistoryAndPersonaOnFirstConsultation() {

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
                                "13836");

                DocumentQueryResponse response = new DocumentQueryResponse(
                                "CEDULA",
                                "12345678",
                                "RUI",
                                "FOUND",
                                "PERSON",
                                person,
                                null,
                                "Consulta realizada con éxito");

                when(consultaDocumentoRepository
                                .findTopByDocumentIdOrderByLastFechaConsultaDesc(
                                                new DocumentId("CEDULA", "12345678")))
                                .thenReturn(Optional.empty());

                adapter.processAndSaveConsultation(response);

                verify(consultaDocumentoRepository).save(any(ConsultaDocumentoEntity.class));
                verify(personaRepository).save(any(PersonaEntity.class));
                verifyNoInteractions(empresaRepository);

                ArgumentCaptor<ConsultaDocumentoEntity> captor = ArgumentCaptor.forClass(ConsultaDocumentoEntity.class);

                verify(consultaDocumentoRepository).save(captor.capture());

                ConsultaDocumentoEntity saved = captor.getValue();

                assertEquals(1, saved.getConsultCount());
                assertEquals("CEDULA", saved.getDocumentId().getDocumentType());
                assertEquals(
                                "12345678",
                                saved.getDocumentId().getDocumentNumber());
        }

        @Test
        void shouldIncrementCountWithoutUpdatingPersonaWhenHashIsEqual() {

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
                                "13836");

                DocumentQueryResponse response = new DocumentQueryResponse(
                                "CEDULA",
                                "12345678",
                                "RUI",
                                "FOUND",
                                "PERSON",
                                person,
                                null,
                                "Consulta realizada con éxito");

                String rawJson = objectMapper.writeValueAsString(response);

                ConsultaDocumentoEntity existing = ConsultaDocumentoEntity.builder()
                                .documentId(
                                                new DocumentId("CEDULA", "12345678"))
                                .externalJsonResponse(rawJson)
                                .responseHash(calculateHash(rawJson))
                                .consultCount(7)
                                .build();

                when(consultaDocumentoRepository
                                .findTopByDocumentIdOrderByLastFechaConsultaDesc(
                                                new DocumentId("CEDULA", "12345678")))
                                .thenReturn(Optional.of(existing));

                adapter.processAndSaveConsultation(response);

                assertEquals(8, existing.getConsultCount());

                verify(consultaDocumentoRepository).save(existing);
                verifyNoInteractions(personaRepository);
                verifyNoInteractions(empresaRepository);
        }

        @Test
        void shouldCreateNewHistoryAndUpdatePersonaWhenHashChanges() {

                PersonBasicData person = new PersonBasicData(
                                "12345678",
                                "LAURA SOFIA GOMEZ TORRES",
                                "LAURA SOFIA",
                                "GOMEZ TORRES",
                                "Femenino",
                                "BOLÍVAR",
                                "TURBACO",
                                null,
                                44,
                                "13836");

                DocumentQueryResponse response = new DocumentQueryResponse(
                                "CEDULA",
                                "12345678",
                                "RUI",
                                "FOUND",
                                "PERSON",
                                person,
                                null,
                                "Consulta realizada con éxito");

                ConsultaDocumentoEntity existing = ConsultaDocumentoEntity.builder()
                                .documentId(
                                                new DocumentId("CEDULA", "12345678"))
                                .externalJsonResponse("{\"old\":true}")
                                .responseHash("old-hash")
                                .consultCount(7)
                                .build();

                when(consultaDocumentoRepository
                                .findTopByDocumentIdOrderByLastFechaConsultaDesc(
                                                new DocumentId("CEDULA", "12345678")))
                                .thenReturn(Optional.of(existing));

                when(personaRepository.findByDocumentId(
                                new DocumentId("CEDULA", "12345678")))
                                .thenReturn(Optional.of(
                                                PersonaEntity.builder()
                                                                .documentId(
                                                                                new DocumentId("CEDULA", "12345678"))
                                                                .fullName("LAURA SOFIA GOMEZ TORRES")
                                                                .age(43)
                                                                .build()));

                adapter.processAndSaveConsultation(response);

                ArgumentCaptor<ConsultaDocumentoEntity> historyCaptor = ArgumentCaptor
                                .forClass(ConsultaDocumentoEntity.class);

                verify(consultaDocumentoRepository, times(1))
                                .save(historyCaptor.capture());

                ConsultaDocumentoEntity newHistory = historyCaptor.getValue();

                assertEquals(1, newHistory.getConsultCount());
                verify(personaRepository).save(any(PersonaEntity.class));
        }

        @Test
        void shouldIncrementCountUsingSameHistoricalVersion() throws Exception {

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
                                "13836");

                DocumentQueryResponse response = new DocumentQueryResponse(
                                "CEDULA",
                                "12345678",
                                "RUI",
                                "FOUND",
                                "PERSON",
                                person,
                                null,
                                "Consulta realizada con éxito");

                String historicalJson = objectMapper.writeValueAsString(response);

                String historicalHash = calculateHash(historicalJson);

                ConsultaDocumentoEntity existing = ConsultaDocumentoEntity.builder()
                                .documentId(
                                                new DocumentId(
                                                                "CEDULA",
                                                                "12345678"))
                                .externalJsonResponse(historicalJson)
                                .responseHash(historicalHash)
                                .consultCount(10)
                                .build();

                when(
                                consultaDocumentoRepository
                                                .findTopByDocumentIdOrderByLastFechaConsultaDesc(
                                                                new DocumentId(
                                                                                "CEDULA",
                                                                                "12345678")))
                                .thenReturn(Optional.of(existing));

                ConsultationMetadata metadata = adapter.processAndSaveConsultation(response);

                assertEquals(11, metadata.consultCount());
                assertEquals(11, existing.getConsultCount());

                verify(consultaDocumentoRepository)
                                .save(existing);

                verifyNoInteractions(personaRepository);
                verifyNoInteractions(empresaRepository);
        }

        /**
         * Los metadatos de consulta cambian en cada llamada, así que no pueden
         * entrar en el hash: si entraran, nunca coincidiría con el registro
         * anterior, se crearía una fila nueva cada vez y consult_count se
         * reiniciaría sin parar.
         *
         * Esta prueba falla si se agrega un metadato a DocumentQueryResponse sin
         * excluirlo del hash.
         */
        @Test
        void hashMustIgnoreConsultationMetadata() {

                DocumentQueryResponse sinMetadatos = new DocumentQueryResponse(
                                "CEDULA",
                                "12345678",
                                "RUI",
                                "FOUND",
                                "PERSON",
                                null,
                                null,
                                "Consulta realizada con éxito");

                DocumentQueryResponse conMetadatos = sinMetadatos.withConsultationMetadata(
                                new ConsultationMetadata(
                                                99,
                                                java.time.OffsetDateTime.now(),
                                                java.time.OffsetDateTime.now().minusDays(3)));

                when(consultaDocumentoRepository
                                .findTopByDocumentIdOrderByLastFechaConsultaDesc(any(DocumentId.class)))
                                .thenReturn(Optional.empty());

                // El hash que guarda el adapter cuando NO hay metadatos.
                adapter.processAndSaveConsultation(sinMetadatos);

                ArgumentCaptor<ConsultaDocumentoEntity> primero = ArgumentCaptor
                                .forClass(ConsultaDocumentoEntity.class);
                verify(consultaDocumentoRepository).save(primero.capture());
                String hashSinMetadatos = primero.getValue().getResponseHash();

                reset(consultaDocumentoRepository, personaRepository, empresaRepository);
                when(consultaDocumentoRepository
                                .findTopByDocumentIdOrderByLastFechaConsultaDesc(any(DocumentId.class)))
                                .thenReturn(Optional.empty());

                adapter.processAndSaveConsultation(conMetadatos);

                ArgumentCaptor<ConsultaDocumentoEntity> segundo = ArgumentCaptor
                                .forClass(ConsultaDocumentoEntity.class);
                verify(consultaDocumentoRepository).save(segundo.capture());

                assertEquals(hashSinMetadatos, segundo.getValue().getResponseHash(),
                                "el hash cambió al incluir metadatos: revisa METADATA_FIELDS en DocumentPersistenceAdapter");
        }

        private String calculateHash(String input) {

                try {
                        var digest = java.security.MessageDigest.getInstance("SHA-256");

                        return java.util.HexFormat.of().formatHex(
                                        digest.digest(
                                                        input.getBytes(
                                                                        java.nio.charset.StandardCharsets.UTF_8)));

                } catch (Exception e) {
                        throw new RuntimeException(e);
                }
        }

}
