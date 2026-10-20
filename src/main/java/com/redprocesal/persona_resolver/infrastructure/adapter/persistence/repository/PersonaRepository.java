package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository;

import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.PersonaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonaRepository extends JpaRepository<PersonaEntity, UUID> {
    
    // Spring Data automáticamente construirá la consulta (SELECT * FROM persona WHERE document_type = ? AND document_number = ?)
    Optional<PersonaEntity> findByDocumentId(DocumentId documentId);
}
