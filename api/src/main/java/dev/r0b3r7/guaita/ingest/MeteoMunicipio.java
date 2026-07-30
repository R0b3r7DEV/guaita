package dev.r0b3r7.guaita.ingest;

import java.time.LocalDate;

/**
 * Una fila de {@code meteo_municipio}: la meteo asignada a un término para una fecha, a las 12:00
 * UTC (criterio EFFIS, docs/04). Es la ENTRADA del FWI. Unidades explícitas en el nombre.
 *
 * <p>{@code interpolado}/{@code nEstaciones} son la CALIDAD del dato: con reanálisis, {@code
 * nEstaciones} es el nº de celdas de rejilla usadas. El cliente de la Fase 2 asigna una celda por
 * municipio (la que contiene su punto interior), así que marca {@code interpolado=true} y {@code
 * nEstaciones=1}; la corrección altitudinal y el IDW multi-celda son de la asignación (commit 4).
 *
 * @param precip24hMm precipitación acumulada en las 24 h que TERMINAN a las 12:00 UTC (no el día
 *     natural): de las 12:00 UTC de ayer a las 12:00 UTC de hoy (doc 04, ventana del FFMC)
 */
public record MeteoMunicipio(
    String ineCode,
    LocalDate fecha,
    double temp12utcC,
    double hr12utcPct,
    double viento12utcKmh,
    double precip24hMm,
    boolean interpolado,
    int nEstaciones,
    String source,
    String sourceUrl) {}
