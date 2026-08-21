package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** comp_vulnerab provisional: normalización de población y combinación con suelo protegido. */
class VulnerabilidadTest {

  private static final ModeloParams.Vulnerab CFG = new ModeloParams.Vulnerab(0.70, 0.30);

  @Test
  void poblacionNormLinealPorElMaximo() {
    assertEquals(1.0, Vulnerabilidad.poblacionNorm(170000, 170000), 1e-9); // la mayor -> 1
    assertEquals(0.0, Vulnerabilidad.poblacionNorm(0, 170000), 1e-9);
    assertEquals(0.5, Vulnerabilidad.poblacionNorm(85000, 170000), 1e-9);
    assertEquals(0.0, Vulnerabilidad.poblacionNorm(1000, 0), 1e-9); // sin máximo -> 0, sin dividir
    assertEquals(1.0, Vulnerabilidad.poblacionNorm(200000, 170000), 1e-9); // acotado a 1
  }

  @Test
  void compVulnerabCombinaConLosPesos() {
    assertEquals(100.0, Vulnerabilidad.compVulnerab(1.0, 1.0, CFG), 1e-9);
    assertEquals(0.0, Vulnerabilidad.compVulnerab(0.0, 0.0, CFG), 1e-9);
    assertEquals(70.0, Vulnerabilidad.compVulnerab(1.0, 0.0, CFG), 1e-9); // solo población
    assertEquals(30.0, Vulnerabilidad.compVulnerab(0.0, 1.0, CFG), 1e-9); // solo protegido
    assertEquals(50.0, Vulnerabilidad.compVulnerab(0.5, 0.5, CFG), 1e-9);
  }
}
