package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository;

import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.EmpresaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmpresaRepository extends JpaRepository<EmpresaEntity, UUID> {
    
    Optional<EmpresaEntity> findByDocumentId(DocumentId documentId);
}
