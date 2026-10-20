package com.redprocesal.persona_resolver.infrastructure.adapter.persistence;

import com.redprocesal.persona_resolver.application.dto.ConsultationMetadata;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;
import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.ConsultaDocumentoEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.ConsultaDocumentoRepository;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.EmpresaRepository;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.PersonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryCleanupTest {

    @Mock
    private ConsultaDocumentoRepository consultaDocumentoRepository;

    @Mock
    private PersonaRepository personaRepository;

    @Mock
    private EmpresaRepository empresaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DocumentPersistenceAdapter adapter;
    private HistoryCleanup cleanup;

    private final DocumentQueryResponse response = new DocumentQueryResponse(
            "CEDULA",
            "12345678",
            "RUI",
            "FOUND",
            "PERSON",
            new PersonBasicData(
                    "12345678",
                    "LAURA SOFIA GOMEZ TORRES",
                    "LAURA SOFIA",
                    "GOMEZ TORRES",
                    "Femenino",
                    "BOLÍVAR",
                    "TURBACO",
                    null,
                    43,
                    "13836"),
            null,
            "Consulta realizada con éxito");

    @BeforeEach
    void setUp() {
        adapter = new DocumentPersistenceAdapter(
                consultaDocumentoRepository,
                personaRepository,
                empresaRepository,
                objectMapper);
        cleanup = new HistoryCleanup(consultaDocumentoRepository, adapter, objectMapper);
    }

    /**
     * Un registro tal como lo dejó la versión anterior: con la clasificación
     * socioeconómica y con las claves en otro orden, como las devuelve jsonb.
     */
    private ConsultaDocumentoEntity legacyRecord(int consultCount) {
        ObjectNode current = (ObjectNode) objectMapper.readTree(adapter.buildHistoricalJson(response));
        ObjectNode person = (ObjectNode) current.get("person");
        person.put("ruiGroup", "C");
        person.put("ruiLevel", "C01");
        person.put("incomeGroup", "Solo ingreso estimado");

        ObjectNode reordered = objectMapper.createObjectNode();
        List<String> keys = new ArrayList<>(current.propertyNames());
        Collections.reverse(keys);
        keys.forEach(key -> reordered.set(key, current.get(key)));

        String legacyJson = objectMapper.writeValueAsString(reordered);

        return ConsultaDocumentoEntity.builder()
                .documentId(new DocumentId("CEDULA", "12345678"))
                .externalJsonResponse(legacyJson)
                .responseHash(adapter.calculateHash(legacyJson))
                .consultCount(consultCount)
                .build();
    }

    @Test
    void shouldRemoveSocioeconomicFieldsAndMatchTheHashOfANewLookup() {
        ConsultaDocumentoEntity record = legacyRecord(5);

        cleanup.clean(record);

        String json = record.getExternalJsonResponse();
        assertFalse(json.contains("ruiGroup"), json);
        assertFalse(json.contains("ruiLevel"), json);
        assertFalse(json.contains("incomeGroup"), json);
        assertFalse(json.contains("Solo ingreso estimado"), json);
        assertEquals(
                adapter.calculateHash(adapter.buildHistoricalJson(response)),
                record.getResponseHash());
    }

    /**
     * Lo que importa en la práctica: tras la limpieza, consultar de nuevo el
     * mismo documento suma sobre su registro en vez de reiniciar el conteo.
     */
    @Test
    void shouldKeepCountingAfterTheCleanup() {
        ConsultaDocumentoEntity record = legacyRecord(5);
        cleanup.clean(record);

        when(consultaDocumentoRepository.findTopByDocumentIdOrderByLastFechaConsultaDesc(
                new DocumentId("CEDULA", "12345678")))
                .thenReturn(Optional.of(record));

        ConsultationMetadata metadata = adapter.processAndSaveConsultation(response);

        assertEquals(6, metadata.consultCount());
        verifyNoInteractions(personaRepository);
    }

    @Test
    void shouldSkipABrokenRecordWithoutStoppingTheOthers() {
        ConsultaDocumentoEntity broken = ConsultaDocumentoEntity.builder()
                .documentId(new DocumentId("CEDULA", "1234567890"))
                .externalJsonResponse("{no es json")
                .responseHash("hash")
                .consultCount(1)
                .build();
        ConsultaDocumentoEntity valid = legacyRecord(2);

        when(consultaDocumentoRepository.findWithSocioeconomicFields())
                .thenReturn(List.of(broken, valid));

        cleanup.run(null);

        verify(consultaDocumentoRepository, never()).save(broken);
        verify(consultaDocumentoRepository).save(valid);
    }

    @Test
    void shouldNotFailStartupWhenTheDatabaseQueryFails() {
        when(consultaDocumentoRepository.findWithSocioeconomicFields())
                .thenThrow(new IllegalStateException("sin conexión"));

        cleanup.run(null);

        verify(consultaDocumentoRepository, never()).save(any());
    }
}
