package com.redprocesal.persona_resolver.infrastructure.adapter.persistence.repository;

import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentId;
import com.redprocesal.persona_resolver.infrastructure.adapter.persistence.entity.DocumentoExcluidoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentoExcluidoRepository extends JpaRepository<DocumentoExcluidoEntity, DocumentId> {
}
