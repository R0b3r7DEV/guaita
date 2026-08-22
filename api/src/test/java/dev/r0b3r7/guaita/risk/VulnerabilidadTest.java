package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.r0b3r7.guaita.risk.Vulnerabilidad.NormaPoblacion;
import org.junit.jupiter.api.Test;

/** comp_vulnerab provisional: normalización de población y combinación con suelo protegido. */
class VulnerabilidadTest {

  private static final ModeloParams.Vulnerab CFG =
      new ModeloParams.Vulnerab(0.70, 0.30, NormaPoblacion.SQRT);

  @Test
  void poblacionNormLineal() {
    assertEquals(1.0, Vulnerabilidad.poblacionNorm(170000, 170000, NormaPoblacion.LINEAL), 1e-9);
    assertEquals(0.5, Vulnerabilidad.poblacionNorm(85000, 170000, NormaPoblacion.LINEAL), 1e-9);
    assertEquals(0.0, Vulnerabilidad.poblacionNorm(0, 170000, NormaPoblacion.LINEAL), 1e-9);
    assertEquals(0.0, Vulnerabilidad.poblacionNorm(1000, 0, NormaPoblacion.LINEAL), 1e-9);
    assertEquals(1.0, Vulnerabilidad.poblacionNorm(200000, 170000, NormaPoblacion.LINEAL), 1e-9);
  }

  @Test
  void poblacionNormSqrtLevantaLosPequenos() {
    assertEquals(1.0, Vulnerabilidad.poblacionNorm(170000, 170000, NormaPoblacion.SQRT), 1e-9);
    // pob/max = 0.01 -> sqrt = 0.1: un municipio pequeño ya discrimina (lineal daría 0.01).
    assertEquals(0.1, Vulnerabilidad.poblacionNorm(1700, 170000, NormaPoblacion.SQRT), 1e-9);
    // sqrt siempre >= lineal para valores en (0,1): mejor discriminación de los medianos.
    double lineal = Vulnerabilidad.poblacionNorm(50000, 170000, NormaPoblacion.LINEAL);
    double sqrt = Vulnerabilidad.poblacionNorm(50000, 170000, NormaPoblacion.SQRT);
    assertTrue(sqrt > lineal);
  }

  @Test
  void poblacionNormLog() {
    assertEquals(1.0, Vulnerabilidad.poblacionNorm(170000, 170000, NormaPoblacion.LOG), 1e-9);
    assertEquals(0.0, Vulnerabilidad.poblacionNorm(0, 170000, NormaPoblacion.LOG), 1e-9);
    // log comprime aún más que sqrt por abajo: pob/max=0.01 sube por encima de 0.1.
    assertTrue(
        Vulnerabilidad.poblacionNorm(1700, 170000, NormaPoblacion.LOG)
            > Vulnerabilidad.poblacionNorm(1700, 170000, NormaPoblacion.SQRT));
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
