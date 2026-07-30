package dev.r0b3r7.guaita.web.tiles;

/**
 * Conversión de índices de tesela XYZ (z/x/y) a envolvente en Web Mercator (EPSG:3857), para
 * alimentar {@code ST_AsMVTGeom}. Aritmética pura, sin estado (ADR-04, docs/01).
 *
 * <p>Esquema <b>XYZ</b> (Google/OSM/MVT), NO TMS: el eje Y crece hacia el <b>sur</b>. La tesela
 * {@code y=0} es la fila más al norte. Invertir este signo produce un mapa que se pinta perfecto
 * pero espejado en latitud —el bug silencioso que esta clase y sus tests existen para impedir: un
 * municipio se dibujaría sobre la geometría de otro sin que nada falle a la vista.
 *
 * <p>El mundo Web Mercator es un cuadrado {@code [-R·π, R·π]} en X e Y, con {@code R = 6378137 m}
 * (radio de la esfera que usa 3857). {@code ORIGIN_SHIFT = R·π}.
 */
public final class TileMath {

  /** Radio de la esfera de Web Mercator (EPSG:3857), en metros. */
  public static final double EARTH_RADIUS = 6378137.0;

  /** Semilado del mundo Web Mercator en metros: {@code π · 6378137 ≈ 20037508,34}. */
  public static final double ORIGIN_SHIFT = Math.PI * EARTH_RADIUS;

  /** Zoom máximo servido (T4, docs/07): por encima se rechaza para no invitar al DoS. */
  public static final int MAX_ZOOM = 16;

  private TileMath() {}

  /** Envolvente de una tesela en EPSG:3857 (metros). */
  public record Bbox(double minX, double minY, double maxX, double maxY) {}

  /** Número de teselas por eje a un zoom dado: {@code 2^z}. */
  public static int tilesPerAxis(int z) {
    if (z < 0 || z > MAX_ZOOM) {
      throw new IllegalArgumentException("zoom fuera de [0," + MAX_ZOOM + "]: " + z);
    }
    return 1 << z; // z<=16 cabe de sobra en int (2^16 = 65536)
  }

  /**
   * Envolvente 3857 de la tesela {@code (z,x,y)}. Valida rangos: {@code z∈[0,MAX_ZOOM]},
   * {@code x,y∈[0,2^z)}. El eje Y se invierte aquí: {@code y=0} produce el borde superior en
   * {@code +ORIGIN_SHIFT} (hemisferio norte).
   */
  public static Bbox tileBounds(int z, int x, int y) {
    validate(z, x, y);
    int n = 1 << z;
    double size = (2.0 * ORIGIN_SHIFT) / n; // lado de la tesela en metros
    double minX = -ORIGIN_SHIFT + x * size;
    double maxX = -ORIGIN_SHIFT + (x + 1) * size;
    // y=0 arriba (norte): el borde superior baja desde +ORIGIN_SHIFT según crece y.
    double maxY = ORIGIN_SHIFT - y * size;
    double minY = ORIGIN_SHIFT - (y + 1) * size;
    return new Bbox(minX, minY, maxX, maxY);
  }

  /**
   * Tesela {@code (x,y)} que contiene un punto lon/lat (grados WGS84) a un zoom dado. Fórmula
   * slippy-map estándar; el suelo (floor) asigna el punto a su tesela. Clampa a {@code [0,2^z)}
   * en los bordes (polos/antimeridiano).
   */
  public static int[] tileForLonLat(int z, double lon, double lat) {
    int n = tilesPerAxis(z);
    double latRad = Math.toRadians(lat);
    int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
    int y = (int) Math.floor((1.0 - asinh(Math.tan(latRad)) / Math.PI) / 2.0 * n);
    return new int[] {clamp(x, n), clamp(y, n)};
  }

  /** Proyecta lon/lat (grados WGS84) a Web Mercator (EPSG:3857), en metros. */
  public static double[] lonLatToMercator(double lon, double lat) {
    double mx = Math.toRadians(lon) * EARTH_RADIUS;
    double my = asinh(Math.tan(Math.toRadians(lat))) * EARTH_RADIUS;
    return new double[] {mx, my};
  }

  /** Tesela {@code (x,y)} que contiene un punto ya en Web Mercator, a un zoom dado. */
  public static int[] tileForMercator(int z, double mx, double my) {
    int n = tilesPerAxis(z);
    double size = (2.0 * ORIGIN_SHIFT) / n;
    int x = (int) Math.floor((mx + ORIGIN_SHIFT) / size);
    int y = (int) Math.floor((ORIGIN_SHIFT - my) / size); // Y invertido: norte arriba
    return new int[] {clamp(x, n), clamp(y, n)};
  }

  /** Área seno hiperbólico: {@code java.lang.Math} no la trae. {@code asinh(v)=ln(v+√(v²+1))}. */
  private static double asinh(double v) {
    return Math.log(v + Math.sqrt(v * v + 1.0));
  }

  private static int clamp(int v, int n) {
    return Math.max(0, Math.min(n - 1, v));
  }

  private static void validate(int z, int x, int y) {
    int n = tilesPerAxis(z); // valida z
    if (x < 0 || x >= n) {
      throw new IllegalArgumentException("x fuera de [0," + n + ") para z=" + z + ": " + x);
    }
    if (y < 0 || y >= n) {
      throw new IllegalArgumentException("y fuera de [0," + n + ") para z=" + z + ": " + y);
    }
  }
}
