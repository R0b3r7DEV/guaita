package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests del {@link FwiCalculator}. El gate de la fase (doc 04 §1):
 *
 * <ol>
 *   <li>Reproduce la TABLA DE EJEMPLO de la publicación original (Van Wagner &amp; Pickett 1985,
 *       Programa F-32, 49 días). Es la referencia primaria; cffdrs se valida contra esta misma
 *       tabla, así que reproducirla es la comprobación más fuerte posible.
 *   <li><b>COBERTURA PENDIENTE</b> (test #2, contraste con cffdrs en R): este entorno no tiene R,
 *       así que no se genera aquí. La tabla de la publicación es la referencia primaria, pero NO
 *       sustituye a cffdrs: 49 días de una estación no ejercitan los casos límite que aportaría
 *       —fronteras de mes en Le/Lf (solo se cruza abril→mayo; jun-dic sin probar), DC en sequía
 *       prolongada (aquí no pasa de ~125), valores extremos—. Además, la reimplementación en Python
 *       usada para inspeccionar no es validación independiente: mismo lector, mismas ecuaciones (un
 *       error de lectura se replicaría idéntico). Pendiente, no cubierto.
 *   <li>Propiedad: lluvia sostenida &gt; 30 mm/día hunde el FFMC al mínimo.
 *   <li>Propiedad: calor sostenido sin lluvia hace crecer el DC monótonamente.
 *   <li>Regresión de la recursión: reanudar desde un estado persistido == cálculo continuo.
 *   <li>Rechazo de entradas imposibles (HR&gt;100, temp&lt;-50, viento negativo, mes inválido).
 * </ol>
 */
class FwiCalculatorTest {

  private record Row(int mon, int day, FwiWeather w, FwiCodes expected) {}

  private static List<Row> referencia() throws IOException {
    InputStream in = FwiCalculatorTest.class.getResourceAsStream("/fwi/vanwagner1985.csv");
    if (in == null) {
      throw new IllegalStateException("falta /fwi/vanwagner1985.csv en el classpath");
    }
    List<Row> rows = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("mon")) {
          continue;
        }
        String[] c = line.split(",");
        FwiWeather w =
            new FwiWeather(
                Double.parseDouble(c[2]),
                Double.parseDouble(c[3]),
                Double.parseDouble(c[4]),
                Double.parseDouble(c[5]));
        FwiCodes exp =
            new FwiCodes(
                Double.parseDouble(c[6]),
                Double.parseDouble(c[7]),
                Double.parseDouble(c[8]),
                Double.parseDouble(c[9]),
                Double.parseDouble(c[10]),
                Double.parseDouble(c[11]));
        rows.add(new Row(Integer.parseInt(c[0]), Integer.parseInt(c[1]), w, exp));
      }
    }
    return rows;
  }

  @Test
  void reproduceLaTablaDeEjemploDeVanWagnerPickett1985() throws IOException {
    // Tolerancia 0,2: el informe imprime a 0,1 y su FORTRAN es de precisión simple; sobre 49 días
    // recursivos una implementación en doble converge a la tabla dentro de esa banda. Un error de
    // fórmula (p. ej. humectación con H en vez de 100-H) divergiría en unidades, no en décimas.
    final double tol = 0.2;
    List<Row> ref = referencia();
    FwiCalculator fwi = FwiCalculator.canada();

    double f = FwiCalculator.STARTUP_FFMC;
    double p = FwiCalculator.STARTUP_DMC;
    double d = FwiCalculator.STARTUP_DC;
    double mFfmc = 0, mDmc = 0, mDc = 0, mIsi = 0, mBui = 0, mFwi = 0;

    for (Row row : ref) {
      FwiCodes got = fwi.step(f, p, d, row.mon(), row.w());
      FwiCodes exp = row.expected();
      mFfmc = Math.max(mFfmc, Math.abs(got.ffmc() - exp.ffmc()));
      mDmc = Math.max(mDmc, Math.abs(got.dmc() - exp.dmc()));
      mDc = Math.max(mDc, Math.abs(got.dc() - exp.dc()));
      mIsi = Math.max(mIsi, Math.abs(got.isi() - exp.isi()));
      mBui = Math.max(mBui, Math.abs(got.bui() - exp.bui()));
      mFwi = Math.max(mFwi, Math.abs(got.fwi() - exp.fwi()));
      f = got.ffmc();
      p = got.dmc();
      d = got.dc();
    }
    System.out.printf(
        "Van Wagner 1985 max|diff| FFMC=%.3f DMC=%.3f DC=%.3f ISI=%.3f BUI=%.3f FWI=%.3f%n",
        mFfmc, mDmc, mDc, mIsi, mBui, mFwi);
    assertTrue(mFfmc <= tol, "FFMC diverge de la publicacion: " + mFfmc);
    assertTrue(mDmc <= tol, "DMC diverge de la publicacion: " + mDmc);
    assertTrue(mDc <= tol, "DC diverge de la publicacion: " + mDc);
    assertTrue(mIsi <= tol, "ISI diverge de la publicacion: " + mIsi);
    assertTrue(mBui <= tol, "BUI diverge de la publicacion: " + mBui);
    assertTrue(mFwi <= tol, "FWI diverge de la publicacion: " + mFwi);
  }

  @Test
  void lluviaSostenidaHundeElFfmcAlMinimo() {
    FwiCalculator fwi = FwiCalculator.canada();
    double f = FwiCalculator.STARTUP_FFMC;
    double p = FwiCalculator.STARTUP_DMC;
    double d = FwiCalculator.STARTUP_DC;
    FwiWeather lluvia = new FwiWeather(20.0, 100.0, 0.0, 35.0); // >30 mm/dia, aire saturado
    for (int i = 0; i < 20; i++) {
      FwiCodes c = fwi.step(f, p, d, 6, lluvia);
      f = c.ffmc();
      p = c.dmc();
      d = c.dc();
    }
    assertTrue(f < 5.0, "el FFMC no convergio al minimo con lluvia sostenida: " + f);
  }

  @Test
  void calorSostenidoSinLluviaHaceCrecerElDc() {
    FwiCalculator fwi = FwiCalculator.canada();
    double f = FwiCalculator.STARTUP_FFMC;
    double p = FwiCalculator.STARTUP_DMC;
    double d = FwiCalculator.STARTUP_DC;
    double prevDc = d;
    FwiWeather calor = new FwiWeather(30.0, 20.0, 10.0, 0.0);
    for (int i = 0; i < 30; i++) {
      FwiCodes c = fwi.step(f, p, d, 7, calor); // julio
      assertTrue(c.dc() > prevDc, "el DC no crecio en el dia " + i);
      f = c.ffmc();
      p = c.dmc();
      d = c.dc();
      prevDc = d;
    }
  }

  @Test
  void reanudarDesdeEstadoPersistidoIgualQueContinuo() throws IOException {
    List<Row> ref = referencia();
    FwiCalculator fwi = FwiCalculator.canada();

    List<FwiCodes> continuo = new ArrayList<>();
    double f = FwiCalculator.STARTUP_FFMC;
    double p = FwiCalculator.STARTUP_DMC;
    double d = FwiCalculator.STARTUP_DC;
    for (Row row : ref) {
      FwiCodes c = fwi.step(f, p, d, row.mon(), row.w());
      continuo.add(c);
      f = c.ffmc();
      p = c.dmc();
      d = c.dc();
    }

    // Reanudar desde el estado persistido del dia k debe dar lo mismo que el calculo continuo.
    int k = 20;
    double rf = continuo.get(k).ffmc();
    double rp = continuo.get(k).dmc();
    double rd = continuo.get(k).dc();
    for (int i = k + 1; i < ref.size(); i++) {
      FwiCodes got = fwi.step(rf, rp, rd, ref.get(i).mon(), ref.get(i).w());
      FwiCodes exp = continuo.get(i);
      assertTrue(
          Math.abs(got.ffmc() - exp.ffmc()) < 1e-9
              && Math.abs(got.dmc() - exp.dmc()) < 1e-9
              && Math.abs(got.dc() - exp.dc()) < 1e-9
              && Math.abs(got.fwi() - exp.fwi()) < 1e-9,
          "reanudar difiere del calculo continuo en i=" + i);
      rf = got.ffmc();
      rp = got.dmc();
      rd = got.dc();
    }
  }

  @Test
  void rechazaEntradasImposibles() {
    FwiCalculator fwi = FwiCalculator.canada();
    assertThrows(
        IllegalArgumentException.class,
        () -> fwi.step(85, 6, 15, 6, new FwiWeather(20, 105, 10, 0))); // HR > 100
    assertThrows(
        IllegalArgumentException.class,
        () -> fwi.step(85, 6, 15, 6, new FwiWeather(-60, 50, 10, 0))); // temp < -50
    assertThrows(
        IllegalArgumentException.class,
        () -> fwi.step(85, 6, 15, 6, new FwiWeather(20, 50, -5, 0))); // viento negativo
    assertThrows(
        IllegalArgumentException.class,
        () -> fwi.step(85, 6, 15, 13, new FwiWeather(20, 50, 10, 0))); // mes fuera de 1..12
  }
}
