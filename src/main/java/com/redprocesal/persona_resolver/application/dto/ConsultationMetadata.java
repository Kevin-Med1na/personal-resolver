package com.redprocesal.persona_resolver.application.dto;

import java.time.OffsetDateTime;

/**
 * Metadatos de historial que acompañan una consulta.
 *
 * Las fechas se exponen como {@link OffsetDateTime} en UTC, no como
 * {@code LocalDateTime}: un instante sin zona viaja al cliente sin forma de
 * saber a qué reloj pertenece, y el navegador lo interpreta como hora local. Eso
 * mostraba las consultas cinco horas adelantadas, porque el backend corre en UTC
 * y el usuario está en UTC-5.
 *
 * @param consultCount        número de veces consultado con esta misma
 *                            información.
 * @param firstConsultDate    primera vez que se consultó el documento.
 * @param previousConsultDate consulta inmediatamente anterior a la actual, o
 *                            {@code null} si esta es la primera. Deliberadamente
 *                            no es la consulta en curso: informar "consultado
 *                            ahora mismo" no le dice nada a quien acaba de
 *                            consultar.
 */
public record ConsultationMetadata(
        Integer consultCount,
        OffsetDateTime firstConsultDate,
        OffsetDateTime previousConsultDate
) {
}
