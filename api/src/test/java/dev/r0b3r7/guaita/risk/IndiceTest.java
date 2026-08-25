package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Índice: media geométrica v1.0, modulador v1.1 (meteo base) y niveles. */
class IndiceTest {

  // v1.1: anclaje 48,3, pendiente 0,00455, banda [0,85..1,15].
  private static final ModeloParams.Indice CFG =
      new ModeloParams.Indice(0.65, 0.35, 48.3, 0.00455, 0.85, 1.15, List.of(20, 40, 60, 80, 100));

  @Test
  void mediaGeometrica() {
    // día lluvioso: comp_meteo=0 -> índice 0 por mucho combustible que haya
    assertEquals(0.0, Indice.calcular(0.0, 100.0, 100.0, CFG), 1e-9);
    // los tres al máximo -> 100
    assertEquals(100.0, Indice.calcular(100.0, 100.0, 100.0, CFG), 1e-9);
    // combustible sin meteo tampoco arde
    assertEquals(0.0, Indice.calcular(100.0, 0.0, 0.0, CFG), 1e-9);
  }

  @Test
  void estructuralAltoConMeteoBajoDaIndiceBajo() {
    // comp_meteo=10, estructural=100, vulnerab=0: sqrt(10)*sqrt(65) ~ 25.5
    double idx = Indice.calcular(10.0, 100.0, 0.0, CFG);
    assertEquals(Math.sqrt(10.0) * Math.sqrt(65.0), idx, 1e-9);
    assertTrue(idx < 30.0, "meteo baja debe hundir el índice pese al estructural máximo");
  }

  @Test
  void moduladorV11() {
    // en el anclaje (ce=48,3) el modulador es 1 -> índice = meteo
    assertEquals(50.0, Indice.calcularV11(50.0, 48.3, CFG), 1e-9);
    // meteo=0 -> 0, gane lo que gane la estructura
    assertEquals(0.0, Indice.calcularV11(0.0, 100.0, CFG), 1e-9);
    // estructura muy alta -> modulador saturado a max 1,15
    assertEquals(50.0 * 1.15, Indice.calcularV11(50.0, 1000.0, CFG), 1e-9);
    // estructura muy baja -> modulador saturado a min 0,85
    assertEquals(50.0 * 0.85, Indice.calcularV11(50.0, -1000.0, CFG), 1e-9);
    // la estructura solo MODULA: no puede hundir la meteo a cero ni dispararla sola
    assertTrue(Indice.calcularV11(40.0, 10.0, CFG) >= 40.0 * 0.85);
  }

  @Test
  void niveles() {
    assertEquals(1, Indice.nivel(0.0, CFG.niveles()));
    assertEquals(1, Indice.nivel(20.0, CFG.niveles())); // tope inferior cae en su tramo
    assertEquals(2, Indice.nivel(20.01, CFG.niveles()));
    assertEquals(3, Indice.nivel(55.0, CFG.niveles()));
    assertEquals(5, Indice.nivel(100.0, CFG.niveles()));
  }
}
