package dev.r0b3r7.guaita.risk;

/**
 * Meteo diaria al mediodía solar que come el FWI (doc 04 §1). Unidades EXPLÍCITas en el nombre: el
 * viento va en km/h, no m/s —si se cuela m/s el FWI sale bajo y nadie se entera (doc 04 §1)—.
 *
 * @param tempC temperatura, °C
 * @param rhPct humedad relativa, %
 * @param windKmh velocidad de viento, km/h
 * @param rainMm precipitación acumulada 24 h, mm
 */
public record FwiWeather(double tempC, double rhPct, double windKmh, double rainMm) {}
