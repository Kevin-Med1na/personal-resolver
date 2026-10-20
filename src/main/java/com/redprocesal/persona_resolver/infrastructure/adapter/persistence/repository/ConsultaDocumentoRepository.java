package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository;

import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.ConsultaDocumentoEntity;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsultaDocumentoRepository extends JpaRepository<ConsultaDocumentoEntity, UUID> {
    
    // Este método es clave para la lógica: buscará el historial más reciente de un documento
    // ordenado por la última fecha de consulta de forma descendente.
    // Así podremos sacar su "responseHash" y compararlo con la nueva consulta.
    Optional<ConsultaDocumentoEntity> findTopByDocumentIdOrderByLastFechaConsultaDesc(DocumentId documentId);

    // La primera vez que se vio el documento, que puede estar en una version
    // anterior del historial: cada cambio de informacion abre un registro nuevo
    // con su propia first_consult_date.
    Optional<ConsultaDocumentoEntity> findTopByDocumentIdOrderByPrimeraFechaConsultaAsc(DocumentId documentId);

}