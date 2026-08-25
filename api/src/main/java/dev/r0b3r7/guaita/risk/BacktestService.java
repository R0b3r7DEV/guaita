package dev.r0b3r7.guaita.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
      select extract(year from ip.fecha)::int anio, extract(doy from ip.fecha)::int doy,
             ip.indice, ip.comp_meteo cm,
             100.0 * cume_dist() over (order by f.fwi) cm_abs,
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

  // Corrección de contaminación de etiquetas (b), con perímetros reales: un parte del EGIF es UN
  // municipio de inicio, pero el fuego quema varios (Bejís marcó 9). Esos términos afectados
  // —los que el PERÍMETRO del propio parte cubre con >=10 ha, salvo el de inicio— quedan FUERA
  // de los negativos durante la ventana del incendio (ni positivos ni negativos): su día de quema
  // no es un "no pasó nada" honesto. Enlace parte<->perímetro por numpif = numeroparte. Sustituye
  // a la vieja aproximación por vecindad (ST_Touches), que excluía vecinos aunque no se quemaran.
  private static final String Q_EXCL =
      """
      drop table if exists _excl;
      create temp table _excl as
      select distinct m.ine_code ine, d::date fecha
      from egif_incendio e
      join perimetro_incendio p on p.numpif = e.numeroparte::text
      join municipio m
        on m.ine_code <> e.ine_inicio
       and p.geom && m.geom
       and st_area(st_intersection(p.geom, m.geom)) >= 100000
      join generate_series(e.fecha_inicio, coalesce(e.fecha_fin, e.fecha_inicio),
                           interval '1 day') d on true;
      create index on _excl (ine, fecha);
      """;

  /** Una fila del universo (un par municipio-día) con sus puntuaciones y etiqueta. */
  private record Fila(
      int anio,
      int doy,
      double indice,
      double cm,
      double cmAbs,
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
                  rs.getInt("doy"),
                  rs.getDouble("indice"),
                  rs.getDouble("cm"),
                  rs.getDouble("cm_abs"),
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
    // Índice v1.1 REALMENTE producido y almacenado (verificación sobre producción, docs/09).
    r.add(metrica("indice_v1_1_producido", filas, Fila::indice));
    // Referencias.
    r.add(metrica("baseline_FWI_crudo", filas, Fila::fwi));
    r.add(metrica("baseline_estacional_doy", filas, f -> -Math.abs(f.doy() - 213)));
    // Bloque 1: comp_meteo dentro del compuesto multiplicativo actual. cm=percentil estacional
    // (tira magnitud), cm_abs=percentil sobre la distribución provincial (la conserva), y dos
    // híbridos (media geométrica y máximo de ambos).
    r.add(metrica("compuesto_meteo=percentil", filas, f -> comb(f.cm(), f)));
    r.add(metrica("compuesto_meteo=absoluto", filas, f -> comb(f.cmAbs(), f)));
    r.add(
        metrica(
            "compuesto_meteo=hibrido_geom", filas, f -> comb(Math.sqrt(f.cm() * f.cmAbs()), f)));
    r.add(metrica("compuesto_meteo=hibrido_max", filas, f -> comb(Math.max(f.cm(), f.cmAbs()), f)));
    // Bloque 3: estructura de combinación. Solo meteo (estructura como contexto, fuera del número)
    // y meteo modulada por la estructura en una banda acotada [0,8..1,2] en vez de multiplicador
    // de rango completo.
    r.add(metrica("solo_meteo_percentil", filas, Fila::cm));
    r.add(metrica("solo_meteo_absoluto", filas, Fila::cmAbs));
    r.add(metrica("modulador_ref_0.8_1.2", filas, f -> modulador(f.cmAbs(), f)));
    // Forma FINAL v1.1: meteo absoluta × modulador lineal derivado del tamaño (b=0,00455, banda
    // [0,85..1,15], anclaje mediana provincial 48,3). El arnés la VERIFICA, no la elige.
    r.add(metrica("v1_1_final", filas, f -> f.cmAbs() * modLineal(f.ce())));
    return r;
  }

  // Modulador estructural v1.1: lineal, anclado en la mediana provincial (48,3), banda [0,85-1,15].
  // b=0,00455 DERIVADO del efecto sobre el tamaño (pendiente conservadora 0,0091 del IC 95 %,
  // amortiguada sqrt por la media geométrica), NO ajustado contra la ignición.
  private static double modLineal(double ce) {
    return Math.max(0.85, Math.min(1.15, 1.0 + 0.00455 * (ce - 48.3)));
  }

  // Compuesto multiplicativo (media geométrica): sqrt(meteo)·sqrt(0,65·ce+0,35·cv), meteo dada.
  private static double comb(double meteo, Fila f) {
    double combustibilidad = 0.65 * f.ce() + 0.35 * f.cv();
    return Math.sqrt(Math.max(0.0, meteo)) * Math.sqrt(Math.max(0.0, combustibilidad));
  }

  // Meteo como base, estructura+vulnerab como modulador acotado a [0,8..1,2] (no diluye la meteo).
  private static double modulador(double meteo, Fila f) {
    double s = 0.65 * f.ce() + 0.35 * f.cv(); // 0..100
    return meteo * (0.8 + 0.4 * (s / 100.0));
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

  /**
   * Chequeo operativo que el AUC agregado no captura: qué compuesto sale para Villanueva de Viver
   * (ine 12133) el 2023-03-23 (incendio de 4.700 ha en TEMPORADA BAJA) con cada variante de
   * comp_meteo. Devuelve [cm_percentil, cm_absoluto, idx_percentil, idx_absoluto, idx_hibrido_geom,
   * idx_hibrido_max]. Si una variante lo deja por debajo de nivel 4, es inaceptable operativamente
   * aunque gane en AUC: un sistema que no marca un incendio así no sirve.
   */
  public double[] villanueva20230323() {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select ip.comp_meteo cm, ip.comp_estructural ce, ip.comp_vulnerab cv, f.fwi"
                + " from indice_peligro ip"
                + " join fwi_municipio f on f.ine_code = ip.ine_code and f.fecha = ip.fecha"
                + " where ip.ine_code = '12133' and ip.fecha = '2023-03-23'");
    double cmPct = ((Number) row.get("cm")).doubleValue();
    double ce = ((Number) row.get("ce")).doubleValue();
    double cv = ((Number) row.get("cv")).doubleValue();
    double fwi = ((Number) row.get("fwi")).doubleValue();
    Double cmAbs =
        jdbc.queryForObject(
            "select 100.0 * avg(case when fwi <= ? then 1 else 0 end)"
                + " from fwi_municipio"
                + " where extract(month from fecha) between 3 and 10"
                + " and extract(year from fecha) between 2005 and 2022 and not calentamiento",
            Double.class,
            fwi);
    Fila f = new Fila(2023, 82, 0, cmPct, cmAbs, ce, cv, 0, fwi, 0, false);
    return new double[] {
      cmPct,
      cmAbs,
      comb(cmPct, f),
      comb(cmAbs, f),
      comb(Math.sqrt(cmPct * cmAbs), f),
      comb(Math.max(cmPct, cmAbs), f)
    };
  }
}
