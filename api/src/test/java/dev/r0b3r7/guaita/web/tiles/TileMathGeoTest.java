package dev.r0b3r7.guaita.web.tiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
import dev.r0b3r7.guaita.web.tiles.TileMath.Bbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contraste de {@link TileMath} contra la geometría real en la BD, usando <b>PostGIS como oráculo
 * independiente</b>: es {@code ST_Transform} (4326→3857) quien decide si la envolvente que calcula
 * TileMath cubre Castelló, no la propia aritmética Java de la clase. Si el eje Y estuviera
 * invertido, la envolvente caería en el hemisferio sur y {@code ST_Contains} devolvería false.
 *
 * <p>El contenedor de test no ejecuta el seed (arranca vacío tras las migraciones), así que se
 * inserta una huella real aproximada de Castelló (buffer alrededor de su centro urbano). La fuerza
 * del test no está en la forma exacta sino en el contraste de proyección: el test de integración
 * del endpoint (Fase 1, commit 2) ejercita ya la geometría realmente sembrada.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TileMathGeoTest {

  // Castelló de la Plana (INE 12040), centro urbano aprox. en WGS84.
  private static final double LON = -0.0376;
  private static final double LAT = 39.9864;

  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void sembrarCastello() {
    jdbc.update("delete from municipio where ine_code = '12040'");
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion) "
            + "select '12040', 'Castelló de la Plana', 'La Plana Alta', g,"
            + " st_area(g) / 10000.0, 1 from ("
            + "  select st_multi(st_buffer("
            + "    st_transform(st_setsrid(st_makepoint(?, ?), 4326), 25830), 2000)) g) t",
        LON,
        LAT);
  }

  @Test
  void laTeselaZ12ContieneLaGeometriaRealDeCastello() {
    int[] t = TileMath.tileForLonLat(12, LON, LAT);
    Bbox b = TileMath.tileBounds(12, t[0], t[1]);

    // 1) El punto de referencia cae dentro de la geometría municipal (representa Castelló).
    Boolean puntoEnMuni =
        jdbc.queryForObject(
            "select st_contains(geom,"
                + " st_transform(st_setsrid(st_makepoint(?, ?), 4326), 25830))"
                + " from municipio where ine_code = '12040'",
            Boolean.class,
            LON,
            LAT);
    assertTrue(Boolean.TRUE.equals(puntoEnMuni), "el punto no cae en la geometría de Castelló");

    // 2) Oráculo: la envolvente 3857 de TileMath contiene el punto según PostGIS.
    //    Delata un eje Y invertido (la envolvente caería en el sur).
    Boolean envolventeContienePunto =
        jdbc.queryForObject(
            "select st_contains("
                + " st_makeenvelope(?, ?, ?, ?, 3857),"
                + " st_transform(st_setsrid(st_makepoint(?, ?), 4326), 3857))",
            Boolean.class,
            b.minX(),
            b.minY(),
            b.maxX(),
            b.maxY(),
            LON,
            LAT);
    assertTrue(
        Boolean.TRUE.equals(envolventeContienePunto),
        "la tesela z12 no contiene el punto de Castelló (¿eje Y invertido?)");

    // 3) Y solapa la geometría real cargada en la BD (no un valor a mano).
    Boolean envolventeSolapaGeom =
        jdbc.queryForObject(
            "select st_intersects("
                + " st_makeenvelope(?, ?, ?, ?, 3857),"
                + " st_transform(geom, 3857)) from municipio where ine_code = '12040'",
            Boolean.class,
            b.minX(),
            b.minY(),
            b.maxX(),
            b.maxY());
    assertTrue(
        Boolean.TRUE.equals(envolventeSolapaGeom), "la tesela z12 no solapa la geometría real");
  }
}
