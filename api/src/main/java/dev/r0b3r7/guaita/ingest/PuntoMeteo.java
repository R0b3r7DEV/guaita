package dev.r0b3r7.guaita.ingest;

/**
 * Punto de consulta de un municipio en Open-Meteo (ADR-07).
 *
 * @param ineCode código INE del municipio
 * @param lon longitud WGS84 (ST_PointOnSurface del continente, docs/03)
 * @param lat latitud WGS84
 * @param altitudMediaM altitud media del término (topografia_municipio). Se pide como {@code
 *     elevation} para que Open-Meteo downscalee la temperatura y la humedad AHÍ (evita la doble
 *     contabilidad: Open-Meteo ya aplica −0,65 °C/100 m internamente).
 * @param elevacionCeldaM cota NATIVA del modelo en ese punto (petición de referencia sin {@code
 *     elevation}). Su diferencia con {@code altitudMediaM} es la calidad (docs/04, docs/06)
 */
public record PuntoMeteo(
    String ineCode, double lon, double lat, double altitudMediaM, double elevacionCeldaM) {}
