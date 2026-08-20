package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Funciones puras de la parte estructural estática (sin BD). */
class EstructuralTest {

  @Test
  void fPendienteSeSaturaAPartirDe50Pct() {
    assertEquals(0.5, Estructural.fPendiente(25.0), 1e-9);
    assertEquals(1.0, Estructural.fPendiente(50.0), 1e-9); // satura
    assertEquals(1.0, Estructural.fPendiente(80.0), 1e-9); // clamp
    assertEquals(0.0, Estructural.fPendiente(0.0), 1e-9);
  }

  @Test
  void pesoCoberturaTotalUsaElPesoDelModelo() {
    var r = Estructural.pesoPonderado(100.0, Map.of("4", 100.0), Map.of("4", 1.0), 0.75);
    assertEquals(1.0, r.peso(), 1e-9);
    assertEquals(0.0, r.fracSinCombustible(), 1e-9);
  }

  @Test
  void pesoConHuecoMezclaConElDefecto() {
    // 60 de 100 cubierto por el modelo 4 (peso 1.0); 40 sin dato -> peso-defecto 0.75.
    var r = Estructural.pesoPonderado(100.0, Map.of("4", 60.0), Map.of("4", 1.0), 0.75);
    assertEquals(0.90, r.peso(), 1e-9); // (60*1.0 + 40*0.75)/100
    assertEquals(0.40, r.fracSinCombustible(), 1e-9);
  }

  @Test
  void pesoSinMonteEsCero() {
    var r = Estructural.pesoPonderado(0.0, Map.of(), Map.of("4", 1.0), 0.75);
    assertEquals(0.0, r.peso(), 1e-9);
    assertEquals(0.0, r.fracSinCombustible(), 1e-9);
  }

  @Test
  void parteEstaticaCombina() {
    assertEquals(100.0, Estructural.parteEstatica(1.0, 1.0, 1.0, 1.0), 1e-9);
    // sqrt(0.25)*(0.5)*1.0*(0.6) * 100 = 0.5*0.5*0.6*100 = 15
    assertEquals(15.0, Estructural.parteEstatica(0.25, 0.0, 1.0, 0.0), 1e-9);
  }
}
