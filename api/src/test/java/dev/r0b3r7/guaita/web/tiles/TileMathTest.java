package dev.r0b3r7.guaita.web.tiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.r0b3r7.guaita.web.tiles.TileMath.Bbox;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link TileMath}. No prueban valores escritos a mano (una envolvente hard-coded solo
 * comprueba que la aritmética no cambió, no que sea correcta): prueban <b>invariantes</b>
 * geométricas —el mundo en z=0, la orientación del eje Y, la estabilidad ida y vuelta, y el rechazo
 * de rangos imposibles— más un ancla en un lugar real (Castelló de la Plana).
 */
class TileMathTest {

  private static final double OS = TileMath.ORIGIN_SHIFT;
  private static final double EPS = 1e-6; // metros; OS ~2e7, holgado

  // Castelló de la Plana (INE 12040), centro urbano aprox. en WGS84. El valor exacto no importa:
  // la tesela se calcula A PARTIR del punto, así que el punto siempre cae en su propia tesela; lo
  // que se comprueba es que las dos fórmulas de Mercator (directa e inversa) concuerdan.
  private static final double CASTELLO_LON = -0.0376;
  private static final double CASTELLO_LAT = 39.9864;

  @Test
  void z0EsUnaSolaTeselaQueCubreElMundo() {
    Bbox b = TileMath.tileBounds(0, 0, 0);
    assertEquals(-OS, b.minX(), EPS);
    assertEquals(-OS, b.minY(), EPS);
    assertEquals(OS, b.maxX(), EPS);
    assertEquals(OS, b.maxY(), EPS);
    assertEquals(1, TileMath.tilesPerAxis(0));
  }

  @Test
  void ejeYNoInvertido_teselaY0EsElHemisferioNorte() {
    // z=1: cuatro teselas. y=0 es la fila de arriba -> debe ser el NORTE (Y de 0 a +OS).
    Bbox norte = TileMath.tileBounds(1, 0, 0);
    assertEquals(0.0, norte.minY(), EPS, "el borde inferior de y=0 debe ser el ecuador");
    assertEquals(OS, norte.maxY(), EPS, "el borde superior de y=0 debe ser +ORIGIN_SHIFT");
    assertTrue(norte.maxY() > 0 && norte.minY() >= 0, "y=0 debe estar en el hemisferio norte");

    // y=1 es la fila de abajo -> el SUR. Si estuviera invertido, norte y sur saldrían cambiados.
    Bbox sur = TileMath.tileBounds(1, 0, 1);
    assertEquals(-OS, sur.minY(), EPS);
    assertEquals(0.0, sur.maxY(), EPS);
    assertTrue(sur.maxY() <= 0, "y=1 debe estar en el hemisferio sur");
  }

  @Test
  void idaYVuelta_bboxTeselaBboxEsEstable() {
    // Para varias teselas (esquinas y una real), el centro de su envolvente debe volver a caer en
    // la misma tesela, y su envolvente reconstruida coincidir bit a bit-ish.
    int[][] casos = {{0, 0, 0}, {1, 0, 0}, {1, 1, 1}, {12, 2049, 1536}};
    for (int[] c : casos) {
      int z = c[0], x = c[1], y = c[2];
      Bbox b = TileMath.tileBounds(z, x, y);
      double cx = (b.minX() + b.maxX()) / 2.0;
      double cy = (b.minY() + b.maxY()) / 2.0;
      int[] rt = TileMath.tileForMercator(z, cx, cy);
      assertArrayEquals(new int[] {x, y}, rt, "tesela no estable en z=" + z);
      Bbox b2 = TileMath.tileBounds(z, rt[0], rt[1]);
      assertEquals(b.minX(), b2.minX(), EPS);
      assertEquals(b.minY(), b2.minY(), EPS);
      assertEquals(b.maxX(), b2.maxX(), EPS);
      assertEquals(b.maxY(), b2.maxY(), EPS);
    }
  }

  @Test
  void teselaZ12DeCastelloContieneSuPuntoReal() {
    int[] t = TileMath.tileForLonLat(12, CASTELLO_LON, CASTELLO_LAT);
    Bbox b = TileMath.tileBounds(12, t[0], t[1]);
    double[] m = TileMath.lonLatToMercator(CASTELLO_LON, CASTELLO_LAT);
    assertTrue(b.minX() <= m[0] && m[0] <= b.maxX(), "Castelló fuera de la tesela en X");
    assertTrue(b.minY() <= m[1] && m[1] <= b.maxY(), "Castelló fuera de la tesela en Y");
    assertTrue(m[1] > 0, "Castelló está en el hemisferio norte: su Mercator Y debe ser > 0");
  }

  @Test
  void rechazaZoomFueraDeRango() {
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(-1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(17, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileForLonLat(17, 0, 0));
  }

  @Test
  void rechazaXeYFueraDeRangoParaSuZoom() {
    // z=1 -> índices válidos 0 y 1. 2 está fuera.
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(1, 2, 0));
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(1, 0, 2));
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(1, -1, 0));
    // z=12 -> 2^12 = 4096 índices [0,4095]. 4096 está fuera por uno (off-by-one).
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(12, 4096, 0));
    assertThrows(IllegalArgumentException.class, () -> TileMath.tileBounds(12, 0, 4096));
  }
}
