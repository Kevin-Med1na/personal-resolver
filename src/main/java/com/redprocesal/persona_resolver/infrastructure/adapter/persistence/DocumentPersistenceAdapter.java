package com.redprocesal.persona_resolver.infrastructure.adapter.persistence;

import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;
import com.redprocesal.persona_resolver.application.dto.ConsultationMetadata;
import com.redprocesal.persona_resolver.application.dto.DocumentQueryResponse;
import com.redprocesal.persona_resolver.application.dto.PersonBasicData;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.ConsultaDocumentoEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.EmpresaEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.PersonaEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.ConsultaDocumentoRepository;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.EmpresaRepository;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository.PersonaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentPersistenceAdapter {

    /** Campos de {@link DocumentQueryResponse} que no entran en el hash. */
    private static final List<String> METADATA_FIELDS = List.of(
            "consultCount",
            "firstConsultDate",
            "previousConsultDate"
    );

    private final ConsultaDocumentoRepository consultaDocumentoRepository;
    private final PersonaRepository personaRepository;
    private final EmpresaRepository empresaRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ConsultationMetadata processAndSaveConsultation(
            DocumentQueryResponse response) {

        if (response == null) {
            throw new IllegalArgumentException(
                    "La respuesta de consulta no puede ser nula."
            );
        }

        String rawJsonResponse = buildHistoricalJson(response);
        String currentHash = calculateHash(rawJsonResponse);

        /*
         * UTC explícito en vez de LocalDateTime.now(): el reloj del host no debe
         * decidir el valor almacenado. Si el backend corre en hosts con zonas
         * horarias distintas (un servidor en UTC, un equipo en UTC-5), mezclar
         * ambos relojes en la misma columna hace que el historial deje de ser
         * comparable.
         */
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        DocumentId documentId = new DocumentId(
                response.documentType(),
                response.documentNumber()
        );

        Optional<ConsultaDocumentoEntity> lastConsultOpt =
                consultaDocumentoRepository
                        .findTopByDocumentIdOrderByLastFechaConsultaDesc(
                                documentId
                        );

        /*
         * Se captura ANTES de actualizar el registro: lo que interesa al
         * consumidor es cuándo se consultó por última vez ANTES de ahora. Si se
         * leyera después, siempre valdría "ahora mismo", que no informa nada.
         */
        LocalDateTime previousConsultDate = lastConsultOpt
                .map(ConsultaDocumentoEntity::getLastFechaConsulta)
                .orElse(null);

        LocalDateTime firstConsultDate = consultaDocumentoRepository
                .findTopByDocumentIdOrderByPrimeraFechaConsultaAsc(documentId)
                .map(ConsultaDocumentoEntity::getPrimeraFechaConsulta)
                .orElse(now);

        if (lastConsultOpt.isPresent()) {

            ConsultaDocumentoEntity lastConsult =
                    lastConsultOpt.get();

            if (currentHash.equals(lastConsult.getResponseHash())) {

                int currentCount =
                        lastConsult.getConsultCount() == null
                                ? 0
                                : lastConsult.getConsultCount();

                lastConsult.setConsultCount(currentCount + 1);
                lastConsult.setLastFechaConsulta(now);

                consultaDocumentoRepository.save(lastConsult);

                return metadata(
                        lastConsult.getConsultCount(),
                        firstConsultDate,
                        previousConsultDate
                );
            }
        }

        ConsultaDocumentoEntity newConsult =
                ConsultaDocumentoEntity.builder()
                        .documentId(documentId)
                        .externalJsonResponse(rawJsonResponse)
                        .responseHash(currentHash)
                        .primeraFechaConsulta(now)
                        .lastFechaConsulta(now)
                        .consultCount(1)
                        .build();

        consultaDocumentoRepository.save(newConsult);

        if ("PERSON".equalsIgnoreCase(response.entityType())
                && response.person() != null) {

            upsertPersona(
                    documentId,
                    response.person(),
                    now
            );

        } else if ("COMPANY".equalsIgnoreCase(response.entityType())
                && response.company() != null) {

            upsertEmpresa(
                    documentId,
                    response.company(),
                    now
            );
        }

        return metadata(
                1,
                firstConsultDate,
                previousConsultDate
        );
    }

    /**
     * Las columnas guardan {@code LocalDateTime} en UTC, pero hacia afuera se
     * expone el desplazamiento para que el cliente pueda convertir a su hora
     * local sin adivinar.
     */
    private ConsultationMetadata metadata(
            Integer consultCount,
            LocalDateTime firstConsultDate,
            LocalDateTime previousConsultDate) {

        return new ConsultationMetadata(
                consultCount,
                firstConsultDate == null ? null : firstConsultDate.atOffset(ZoneOffset.UTC),
                previousConsultDate == null ? null : previousConsultDate.atOffset(ZoneOffset.UTC)
        );
    }

    private String buildHistoricalJson(
            DocumentQueryResponse response) {

        try {
            ObjectNode root =
                    objectMapper.valueToTree(response);

            /*
             * Estos campos son metadatos de consulta.
             * NO deben formar parte del JSON histórico
             * ni del hash porque cambiarían en cada consulta.
             *
             * Si se agrega un metadato a DocumentQueryResponse hay que añadirlo
             * aquí: si no, el hash cambia en cada consulta, nunca coincide con el
             * anterior y se crea un registro nuevo cada vez, lo que destruye la
             * deduplicación y reinicia consult_count sin parar.
             */
            METADATA_FIELDS.forEach(root::remove);

            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "No fue posible serializar la respuesta para el historial.",
                    e
            );
        }
    }

    private void upsertPersona(
            DocumentId documentId,
            PersonBasicData person,
            LocalDateTime now) {

        PersonaEntity persona =
                personaRepository
                        .findByDocumentId(documentId)
                        .orElse(
                                PersonaEntity.builder()
                                        .documentId(documentId)
                                        .build()
                        );

        persona.setFullName(person.fullName());
        persona.setGender(person.gender());
        persona.setState(person.state());
        persona.setCity(person.city());
        persona.setAge(person.age());
        persona.setFechaLastConsult(now);

        personaRepository.save(persona);
    }

    private void upsertEmpresa(
            DocumentId documentId,
            CompanyBasicData company,
            LocalDateTime now) {

        EmpresaEntity empresa =
                empresaRepository
                        .findByDocumentId(documentId)
                        .orElse(
                                EmpresaEntity.builder()
                                        .documentId(documentId)
                                        .build()
                        );

        empresa.setCompanyName(company.companyName());
        empresa.setEntityType("COMPANY");
        empresa.setBusinessType(company.businessType());
        empresa.setState(company.state());
        empresa.setCity(company.city());
        empresa.setFechaLastConsult(now);

        empresaRepository.save(empresa);
    }

    private String calculateHash(String input) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            input.getBytes(StandardCharsets.UTF_8)
                    )
            );

        } catch (Exception e) {
            throw new IllegalStateException(
                    "No fue posible calcular el hash SHA-256 de la respuesta.",
                    e
            );
        }
    }
}