package com.redprocesal.persona_resolver.domain.port;

import com.redprocesal.persona_resolver.domain.model.DocumentType;

/**
 * Documentos cuyos titulares pidieron que se borraran sus datos. Un documento
 * excluido no se consulta en las fuentes ni se guarda, así que una consulta
 * posterior no vuelve a recopilarlos.
 */
public interface ExcludedDocuments {

    /** {@code documentNumber} en su forma canónica: ver {@code DocumentQuery#canonicalNumber()}. */
    boolean contains(DocumentType type, String documentNumber);
}
