package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** {@code model-params.yml} carga y bindea a {@link ModeloParams} con los valores de v1.0. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ModeloParamsTest {

  @Autowired private ModeloParams p;

  @Test
  void cargaConLosValoresDeV1() {
    assertEquals("v1.0", p.version());
    assertEquals(15, p.meteo().ventanaDias());

    // Combustible: el modelo 4 es el techo; el hueco sin dato usa un peso FIJO (no la media).
    assertEquals(1.00, p.combustible().pesos().get("4"), 1e-9);
    assertEquals(0.25, p.combustible().pesos().get("8"), 1e-9);
    assertEquals(0.75, p.combustible().pesoDefecto(), 1e-9);
    assertEquals(13, p.combustible().pesos().size(), "los 13 modelos Anderson");

    // f_tiempo: pico = 1.0; sin incendio conocido -> neutro.
    assertEquals(1.00, p.fTiempo().picoValor(), 1e-9);
    assertEquals(1.00, p.fTiempo().sinDatoValor(), 1e-9);

    // Vulnerabilidad provisional e índice.
    assertEquals(0.70, p.vulnerab().pesoPoblacion(), 1e-9);
    assertEquals(0.65, p.indice().pesoEstructural(), 1e-9);
    assertEquals(0.35, p.indice().pesoVulnerab(), 1e-9);
    assertEquals(5, p.indice().niveles().size());
    assertEquals(100, p.indice().niveles().get(4));
  }
}
