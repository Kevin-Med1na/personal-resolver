package com.redprocesal.persona_resolver.infrastructure.adapter.persistence;

import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.ConsultaDocumentoEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.ConsultaDocumentoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Quita del historial la clasificación socioeconómica que se guardaba antes
 * (grupo y nivel RUI, grupo de ingresos) y recalcula el hash de cada registro
 * con el mismo método que usa una consulta nueva. Así la siguiente consulta
 * de un documento sigue sumando sobre su registro en vez de abrir uno nuevo y
 * reiniciar el conteo.
 *
 * Corre al arrancar y es idempotente: solo encuentra registros que todavía
 * tienen alguno de esos campos. Cada registro se guarda por separado, para que
 * uno defectuoso no impida limpiar los demás ni arrancar el servicio.
 */
@Component
@RequiredArgsConstructor
public class HistoryCleanup implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryCleanup.class);

    static final List<String> REMOVED_PERSON_FIELDS = List.of("ruiGroup", "ruiLevel", "incomeGroup");

    private final ConsultaDocumentoRepository consultaDocumentoRepository;
    private final DocumentPersistenceAdapter documentPersistenceAdapter;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<ConsultaDocumentoEntity> pending;

        try {
            pending = consultaDocumentoRepository.findWithSocioeconomicFields();
        } catch (RuntimeException e) {
            LOGGER.error("Historial: no se pudieron buscar registros con datos socioeconómicos; "
                    + "se reintentará en el próximo arranque.", e);
            return;
        }

        if (pending.isEmpty()) {
            return;
        }

        int cleaned = 0;

        for (ConsultaDocumentoEntity consult : pending) {
            try {
                clean(consult);
                consultaDocumentoRepository.save(consult);
                cleaned++;
            } catch (RuntimeException e) {
                // Solo el tipo de error: el mensaje de Jackson puede citar el JSON.
                LOGGER.warn("Historial: no se pudo limpiar el registro {} ({}).",
                        consult.getUuid(), e.getClass().getSimpleName());
            }
        }

        LOGGER.info("Historial: se quitó la clasificación socioeconómica de {} de {} registros.",
                cleaned, pending.size());
    }

    void clean(ConsultaDocumentoEntity consult) {
        ObjectNode root = (ObjectNode) objectMapper.readTree(consult.getExternalJsonResponse());

        if (root.get("person") instanceof ObjectNode person) {
            REMOVED_PERSON_FIELDS.forEach(person::remove);
        }

        // Registros de versiones anteriores pueden traer campos que ya no existen.
        DocumentQueryResponse response = objectMapper
                .readerFor(DocumentQueryResponse.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(root);

        String json = documentPersistenceAdapter.buildHistoricalJson(response);
        consult.setExternalJsonResponse(json);
        consult.setResponseHash(documentPersistenceAdapter.calculateHash(json));
    }
}
