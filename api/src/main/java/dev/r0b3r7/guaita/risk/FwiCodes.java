package dev.r0b3r7.guaita.risk;

/**
 * Los seis códigos e índices del sistema FWI de un día. Los tres códigos de humedad ({@code ffmc},
 * {@code dmc}, {@code dc}) son el ESTADO recursivo que se persiste y alimenta el día siguiente
 * (tabla {@code fwi_municipio}); {@code isi}, {@code bui} y {@code fwi} se derivan de ellos.
 *
 * @param ffmc Fine Fuel Moisture Code (hojarasca, memoria ~2/3 días)
 * @param dmc Duff Moisture Code (capa media, ~12 días)
 * @param dc Drought Code (capa profunda, ~52 días)
 * @param isi Initial Spread Index (propagación)
 * @param bui Buildup Index (combustible disponible)
 * @param fwi Fire Weather Index
 */
public record FwiCodes(double ffmc, double dmc, double dc, double isi, double bui, double fwi) {}
