package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Curva en U de f_tiempo y años-desde-el-último-incendio, con datos sintéticos (sin BD). */
class RegeneracionTest {

  // Parámetros v1.0 (docs/04 §2.4); en producción vienen de model-params.yml.
  private static final ModeloParams.FTiempo C =
      new ModeloParams.FTiempo(3, 0.15, 8, 15, 1.00, 25, 0.70, 1.00, 0.10);

  @Test
  void curvaEnLosAnclajes() {
    assertEquals(0.15, Regeneracion.fTiempo(0, C), 1e-9); // recién quemado: mínimo
    assertEquals(0.15, Regeneracion.fTiempo(3, C), 1e-9); // fin del tramo joven
    assertEquals(1.00, Regeneracion.fTiempo(8, C), 1e-9); // llega al pico
    assertEquals(1.00, Regeneracion.fTiempo(15, C), 1e-9); // fin de la meseta pico
    assertEquals(0.70, Regeneracion.fTiempo(25, C), 1e-9); // fin de la bajada
    assertEquals(0.70, Regeneracion.fTiempo(40, C), 1e-9); // viejo
  }

  @Test
  void curvaEnLaInterpolacion() {
    assertEquals(0.49, Regeneracion.fTiempo(5, C), 1e-9); // subida: 0.15 + 0.85*(5-3)/5
    assertEquals(0.85, Regeneracion.fTiempo(20, C), 1e-9); // bajada: 1.0 - 0.30*(20-15)/10
    // monótona: sube hasta el pico, baja después
    assertTrue(Regeneracion.fTiempo(6, C) > Regeneracion.fTiempo(5, C));
    assertTrue(Regeneracion.fTiempo(22, C) < Regeneracion.fTiempo(18, C));
  }

  @Test
  void aniosDesdeElUltimoIncendioDependeDeLaFecha() {
    List<LocalDate> incendios =
        List.of(LocalDate.parse("2012-06-01"), LocalDate.parse("2022-08-15"));
    // en 2026 manda el de 2022
    assertEquals(
        4,
        Regeneracion.aniosDesdeUltimoIncendio(LocalDate.parse("2026-08-21"), incendios).getAsInt());
    // en 2015 solo cualifica el de 2012 -> distinto valor para el MISMO municipio
    assertEquals(
        2,
        Regeneracion.aniosDesdeUltimoIncendio(LocalDate.parse("2015-01-01"), incendios).getAsInt());
  }

  @Test
  void sinIncendiosOSoloFuturosDaVacio() {
    assertFalse(
        Regeneracion.aniosDesdeUltimoIncendio(LocalDate.parse("2026-01-01"), List.of())
            .isPresent());
    OptionalInt soloFuturo =
        Regeneracion.aniosDesdeUltimoIncendio(
            LocalDate.parse("2020-01-01"), List.of(LocalDate.parse("2022-08-15")));
    assertFalse(soloFuturo.isPresent());
  }
}
