package dev.r0b3r7.guaita.ingest;

import java.time.LocalDate;

/**
 * Una fila de {@code meteo_municipio}: la meteo asignada a un término para una fecha, a las 12:00
 * UTC (criterio EFFIS, docs/04). Es la ENTRADA del FWI. Unidades explícitas en el nombre.
 *
 * <p>La CALIDAD del dato es {@code elevacionCeldaM}/{@code deltaAltitudM}: con reanálisis no hay
 * estaciones; importa cuánto downscaleó Open-Meteo desde la cota de su celda hasta la altitud media
 * del municipio. A mayor {@code |deltaAltitudM|}, menos fiable (docs/04, docs/06).
 *
 * @param precip24hMm precipitación acumulada en las 24 h que TERMINAN a las 12:00 UTC (no el día
 *     natural): de las 12:00 UTC de ayer a las 12:00 UTC de hoy (doc 04, ventana del FFMC)
 * @param elevacionCeldaM cota nativa del modelo en el punto (m)
 * @param deltaAltitudM altitud media del municipio − cota de la celda (m); magnitud del downscaling
 */
public record MeteoMunicipio(
    String ineCode,
    LocalDate fecha,
    double temp12utcC,
    double hr12utcPct,
    double viento12utcKmh,
    double precip24hMm,
    double elevacionCeldaM,
    double deltaAltitudM,
    String source,
    String sourceUrl) {}
