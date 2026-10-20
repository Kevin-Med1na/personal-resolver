package com.redprocesal.persona_resolver.domain.exception;

/**
 * La fuente externa no pudo alcanzarse: el canal está caído, no responde o
 * rechaza la conexión.
 *
 * Se distingue de {@link ExternalSourceException} porque no indica un problema
 * con el contrato de la fuente, sino con la disponibilidad del canal. El
 * consumidor puede reintentar; ante un {@link ExternalSourceException} no
 * serviría de nada.
 */
public class SourceUnavailableException extends ExternalSourceException {

    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
