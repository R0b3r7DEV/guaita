package dev.r0b3r7.guaita.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orquestación del backtest (docs/09): evalúa el índice YA CALCULADO (indice_peligro) contra los
 * positivos del EGIF (egif_incendio), SIN fijar pesos. Partición temporal (nunca aleatoria), líneas
 * base (FWI crudo, temperatura), ablación por componente e IC por bootstrap. Corrección de
 * contaminación de etiquetas (un parte no es un municipio): los términos VECINOS del de inicio, en
 * la ventana del incendio, se EXCLUYEN de los negativos (ni positivos ni negativos).
 */
@Service
public class BacktestService {

  // Temporada de riesgo marzo-octubre, 2005-2022. Índice + componentes + FWI crudo + temperatura.
  private static final String Q_UNIVERSO =
      """
      select extract(year from ip.fecha)::int anio, ip.indice, ip.comp_meteo cm,
             ip.comp_estructural ce, ip.comp_vulnerab cv, ip.nivel, f.fwi, m.temp_12utc_c tmax,
             (exists (select 1 from egif_incendio e
                      where e.ine_inicio = ip.ine_code and e.fecha_inicio = ip.fecha)) pos,
             (exists (select 1 from _excl x
                      where x.ine = ip.ine_code and x.fecha = ip.fecha)) excl
      from indice_peligro ip
      join fwi_municipio f on f.ine_code = ip.ine_code and f.fecha = ip.fecha
      join meteo_municipio m on m.ine_code = ip.ine_code and m.fecha = ip.fecha
      where extract(month from ip.fecha) between 3 and 10
        and extract(year from ip.fecha) between 2005 and 2022
      """;

  // Vecinos (ST_Touches) del término de inicio, por cada día de la ventana del incendio.
  private static final String Q_EXCL =
      """
      drop table if exists _excl;
      create temp table _excl as
      select distinct v.ine_code ine, d::date fecha
      from egif_incendio e
      join municipio o on o.ine_code = e.ine_inicio
      join municipio v on v.ine_code <> o.ine_code and st_touches(v.geom, o.geom)
      join generate_series(e.fecha_inicio, coalesce(e.fecha_fin, e.fecha_inicio),
                           interval '1 day') d on true;
      create index on _excl (ine, fecha);
      """;

  /** Una fila del universo (un par municipio-día) con sus puntuaciones y etiqueta. */
  private record Fila(
      int anio,
      double indice,
      double cm,
      double ce,
      double cv,
      int nivel,
      double fwi,
      double tmax,
      boolean pos) {}

  /** Métricas de una variante en un periodo. */
  public record Metrica(
      String variante, double auc, double aucLo, double aucHi, double aucPr, int nPos, int nNeg) {}

  private final JdbcTemplate jdbc;

  public BacktestService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Ejecuta el backtest. corteCalib = último año de calibración (validación = &gt; corte). */
  public List<Metrica>[] ejecutar(int corteCalib) {
    jdbc.execute(Q_EXCL);
    List<Fila> calib = new ArrayList<>();
    List<Fila> valid = new ArrayList<>();
    jdbc.query(
        Q_UNIVERSO,
        (java.sql.ResultSet rs) -> {
          if (rs.getBoolean("excl") && !rs.getBoolean("pos")) {
            return; // vecino en la ventana: fuera de los negativos
          }
          Fila f =
              new Fila(
                  rs.getInt("anio"),
                  rs.getDouble("indice"),
                  rs.getDouble("cm"),
                  rs.getDouble("ce"),
                  rs.getDouble("cv"),
                  rs.getInt("nivel"),
                  rs.getDouble("fwi"),
                  rs.getDouble("tmax"),
                  rs.getBoolean("pos"));
          (f.anio() <= corteCalib ? calib : valid).add(f);
        });
    @SuppressWarnings("unchecked")
    List<Metrica>[] out = new List[] {metricas(calib), metricas(valid)};
    return out;
  }

  private List<Metrica> metricas(List<Fila> filas) {
    List<Metrica> r = new ArrayList<>();
    r.add(metrica("indice_compuesto", filas, Fila::indice));
    r.add(metrica("baseline_FWI_crudo", filas, Fila::fwi));
    r.add(metrica("baseline_Tmax", filas, Fila::tmax));
    r.add(metrica("ablacion_sin_meteo", filas, f -> 0.65 * f.ce() + 0.35 * f.cv()));
    r.add(metrica("ablacion_sin_estructural", filas, f -> Math.sqrt(f.cm()) * Math.sqrt(f.cv())));
    r.add(metrica("ablacion_sin_vulnerab", filas, f -> Math.sqrt(f.cm()) * Math.sqrt(f.ce())));
    return r;
  }

  private Metrica metrica(String nombre, List<Fila> filas, ToDoubleFunction<Fila> score) {
    List<Double> pos = new ArrayList<>();
    List<Double> neg = new ArrayList<>();
    for (Fila f : filas) {
      (f.pos() ? pos : neg).add(score.applyAsDouble(f));
    }
    if (pos.isEmpty() || neg.isEmpty()) {
      return new Metrica(
          nombre, Double.NaN, Double.NaN, Double.NaN, Double.NaN, pos.size(), neg.size());
    }
    double[] p = pos.stream().mapToDouble(Double::doubleValue).toArray();
    double[] n = neg.stream().mapToDouble(Double::doubleValue).toArray();
    double[] ic = Backtest.aucRocIc(p, n, 2000, 20260824L, 0.05);
    double[] todo = new double[p.length + n.length];
    boolean[] etq = new boolean[todo.length];
    for (int i = 0; i < p.length; i++) {
      todo[i] = p[i];
      etq[i] = true;
    }
    System.arraycopy(n, 0, todo, p.length, n.length);
    double ap = Backtest.aucPr(todo, etq);
    return new Metrica(nombre, ic[0], ic[1], ic[2], ap, p.length, n.length);
  }

  /** Sensibilidad y falsa alarma del compuesto a nivel ≥ 4, en un periodo. */
  public double[] sensYFalsaAlarma(int corteCalib, boolean validacion) {
    jdbc.execute(Q_EXCL);
    long nPos = 0;
    long posEnAlto = 0;
    long marcados = 0;
    long falsos = 0;
    List<long[]> acc = new ArrayList<>();
    acc.add(new long[4]);
    jdbc.query(
        Q_UNIVERSO,
        (java.sql.ResultSet rs) -> {
          if (rs.getBoolean("excl") && !rs.getBoolean("pos")) {
            return;
          }
          boolean esValid = rs.getInt("anio") > corteCalib;
          if (esValid != validacion) {
            return;
          }
          boolean pos = rs.getBoolean("pos");
          boolean alto = rs.getInt("nivel") >= 4;
          long[] a = acc.get(0);
          if (pos) {
            a[0]++;
            if (alto) {
              a[1]++;
            }
          }
          if (alto) {
            a[2]++;
            if (!pos) {
              a[3]++;
            }
          }
        });
    long[] a = acc.get(0);
    nPos = a[0];
    posEnAlto = a[1];
    marcados = a[2];
    falsos = a[3];
    double sens = nPos == 0 ? Double.NaN : (double) posEnAlto / nPos;
    double fa = marcados == 0 ? Double.NaN : (double) falsos / marcados;
    return new double[] {sens, fa, nPos, marcados};
  }
}
