package dev.r0b3r7.guaita.risk;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Precálculo y consulta de la climatología FWI congelada ({@code fwi_climatologia}, docs/04 §1). Los
 * breakpoints P0..P100 se calculan por municipio × día del año sobre la ventana ±15 días del periodo
 * base, con aritmética de día del año CIRCULAR (la ventana del 1-ene incluye finales de diciembre).
 * El borde del año y el 29-feb (doy 366, solo 5 bisiestos) quedan bien muestreados justamente porque
 * la referencia es una VENTANA de ±15 días, no el doy exacto: cada doy recibe ~30 días/año de sus
 * vecinos. Se ejecuta una vez por versión; el scheduler no la toca.
 */
@Service
public class ClimatologiaService {

  // Precálculo POR MUNICIPIO (evita una agregación provincial de decenas de millones de filas):
  // expande cada día del FWI a los 31 días-objetivo de su ventana ±15 (circular) y saca los 101
  // cuantiles por día-objetivo. `not calentamiento` excluye el arranque (docs/04 §1).
  private static final String INSERT_UN_MUNICIPIO =
      """
      insert into fwi_climatologia
        (ine_code, doy, version_modelo, breakpoints, base_desde, base_hasta)
      select ?, e.target_doy, ?,
             (percentile_cont(array(select i / 100.0 from generate_series(0, 100) i))
                within group (order by e.fwi))::numeric(6,2)[],
             ?, ?
      from (
        select ((extract(doy from f.fecha)::int - 1 + o.off + 366) % 366) + 1 as target_doy, f.fwi
        from fwi_municipio f
        cross join generate_series(?, ?) o(off)
        where f.ine_code = ? and f.fecha between ? and ? and not f.calentamiento
      ) e
      group by e.target_doy
      """;

  private final JdbcTemplate jdbc;

  public ClimatologiaService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** (Re)calcula la climatología de una versión sobre el periodo base. Idempotente por versión. */
  @Transactional
  public int precomputar(String version, int ventana, LocalDate desde, LocalDate hasta) {
    jdbc.update("delete from fwi_climatologia where version_modelo = ?", version);
    List<String> munis =
        jdbc.queryForList("select ine_code from municipio order by ine_code", String.class);
    int total = 0;
    for (String ine : munis) {
      total +=
          jdbc.update(
              INSERT_UN_MUNICIPIO, ine, version, desde, hasta, -ventana, ventana, ine, desde, hasta);
    }
    return total;
  }

  /** Invariantes del precálculo. Devuelve la lista de fallos (vacía = OK). */
  public List<String> asserciones(String version) {
    List<String> fallos = new ArrayList<>();
    Integer provincia = jdbc.queryForObject("select count(*) from municipio", Integer.class);
    Integer filas =
        jdbc.queryForObject(
            "select count(*) from fwi_climatologia where version_modelo = ?", Integer.class, version);
    int esperado = (provincia == null ? 0 : provincia) * 366;
    if (filas == null || filas != esperado) {
      fallos.add(filas + " filas, esperaba " + esperado + " (municipios × 366)");
    }
    Integer malArray =
        jdbc.queryForObject(
            "select count(*) from fwi_climatologia where version_modelo = ?"
                + " and (breakpoints is null or array_length(breakpoints, 1) <> 101)",
            Integer.class,
            version);
    if (malArray != null && malArray > 0) {
      fallos.add(malArray + " filas con breakpoints NULL o de longitud <> 101");
    }
    Integer noMono =
        jdbc.queryForObject(
            "select count(*) from (select breakpoints[i] a, breakpoints[i + 1] b"
                + " from fwi_climatologia, generate_series(1, 100) i where version_modelo = ?) t"
                + " where a > b",
            Integer.class,
            version);
    if (noMono != null && noMono > 0) {
      fallos.add(noMono + " pares de breakpoints no monótonos (P_i > P_{i+1})");
    }
    return fallos;
  }

  /** Percentil de un evento (municipio, fecha) por la nueva ruta, o {@code null} si falta el dato. */
  public Double percentilEvento(String ineCode, LocalDate fecha, String version) {
    Double fwi =
        jdbc.query(
            "select fwi from fwi_municipio where ine_code = ? and fecha = ?",
            rs -> rs.next() ? rs.getDouble(1) : null,
            ineCode,
            fecha);
    if (fwi == null) {
      return null;
    }
    double[] bp =
        jdbc.query(
            "select breakpoints from fwi_climatologia where ine_code = ? and doy = ?"
                + " and version_modelo = ?",
            rs -> rs.next() ? aDoubles(rs.getArray(1)) : null,
            ineCode,
            fecha.getDayOfYear(),
            version);
    return bp == null ? null : Climatologia.percentil(fwi, bp);
  }

  private static double[] aDoubles(Array sqlArray) throws SQLException {
    Object[] o = (Object[]) sqlArray.getArray();
    double[] d = new double[o.length];
    for (int i = 0; i < o.length; i++) {
      d[i] = ((Number) o[i]).doubleValue();
    }
    return d;
  }
}
