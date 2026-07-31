package dev.r0b3r7.guaita.ingest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aserciones del backfill e informe {@code etl/reports/fwi-backfill.md} (docs/04, punto de entrega
 * del commit 5). Las aserciones fallan RUIDOSAMENTE; el informe valida contra eventos reales.
 */
@Component
public class FwiBackfillReport {

  /** Un incendio semilla: municipio, INE y fecha (docs/03). */
  private record Evento(String nombre, String ineCode, LocalDate fecha) {}

  private static final List<Evento> EVENTOS =
      List.of(
          new Evento("Bejís", "12022", LocalDate.parse("2022-08-15")),
          new Evento("Jérica", "12071", LocalDate.parse("2022-08-15")),
          new Evento("Viver", "12140", LocalDate.parse("2022-08-15")),
          new Evento("Torás", "12114", LocalDate.parse("2022-08-15")),
          new Evento("les Useres", "12122", LocalDate.parse("2022-08-15")),
          new Evento("Costur", "12049", LocalDate.parse("2022-08-15")),
          new Evento("Figueroles", "12060", LocalDate.parse("2022-08-15")),
          new Evento("Llucena", "12072", LocalDate.parse("2022-08-15")),
          new Evento("Villanueva de Viver", "12133", LocalDate.parse("2023-03-23")),
          new Evento("la Vall d'Uixó", "12126", LocalDate.parse("2026-07-25")));

  private static final Map<String, String> CONTROL =
      Map.of(
          "12077", "Moncofa",
          "12082", "Nules",
          "12011", "Almenara",
          "12080", "Morella",
          "12139", "Vistabella",
          "12130", "Villahermosa");

  private final JdbcTemplate jdbc;

  public FwiBackfillReport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Comprueba las invariantes del backfill. Lanza con la lista de fallos si alguna falla. */
  public void asserciones() {
    List<String> fallos = new ArrayList<>();

    Integer nulos =
        jdbc.queryForObject(
            "select count(*) from fwi_municipio where fwi < 0 or 'NaN' = fwi or dc < 0",
            Integer.class);
    if (nulos != null && nulos > 0) {
      fallos.add(nulos + " filas con FWI/DC negativo o NaN");
    }

    // Sin huecos: cada municipio debe tener días consecutivos (max-min+1 == count).
    Integer huecos =
        jdbc.queryForObject(
            "select count(*) from (select ine_code, count(*) c,"
                + " (max(fecha) - min(fecha) + 1) esperado from fwi_municipio group by ine_code) t"
                + " where c <> esperado",
            Integer.class);
    if (huecos != null && huecos > 0) {
      fallos.add(huecos + " municipios con huecos en la serie");
    }

    // Calentamiento: exactamente los ~30 primeros días de la serie por municipio.
    Integer calMal =
        jdbc.queryForObject(
            "select count(*) from (select ine_code, count(*) filter (where calentamiento) c"
                + " from fwi_municipio group by ine_code) t where c <> 30",
            Integer.class);
    if (calMal != null && calMal > 0) {
      fallos.add(calMal + " municipios sin exactamente 30 días de calentamiento");
    }

    // Reinicio silencioso del DC: caída brusca (de >50 a <20) SIN lluvia que lo justifique (<5 mm).
    Integer reset =
        jdbc.queryForObject(
            "select count(*) from (select f.dc,"
                + " lag(f.dc) over (partition by f.ine_code order by f.fecha) prev,"
                + " m.precip_24h_mm p from fwi_municipio f join meteo_municipio m using (ine_code,"
                + " fecha) where not f.calentamiento) t where prev > 50 and dc < 20 and p < 5",
            Integer.class);
    if (reset != null && reset > 0) {
      fallos.add(reset + " posibles reinicios silenciosos del DC (caída a ~15 sin lluvia)");
    }

    if (!fallos.isEmpty()) {
      throw new IllegalStateException("Aserciones del backfill FALLIDAS: " + fallos);
    }
  }

  /** Genera el informe en markdown. */
  public String informe() {
    StringBuilder md = new StringBuilder();
    md.append("# Informe del backfill FWI\n\n");
    md.append("Generado tras el backfill (docs/04). Serie continua, calentamiento excluido.\n\n");

    md.append("## a) Por año (provincial, sin calentamiento)\n\n");
    md.append("| año | FWI medio | máx | P90 | P95 | P99 |\n|---|---|---|---|---|---|\n");
    jdbc.query(
        "select extract(year from fecha)::int y, round(avg(fwi),1) med, round(max(fwi),1) mx,"
            + " round((percentile_cont(0.90) within group (order by fwi))::numeric,1) p90,"
            + " round((percentile_cont(0.95) within group (order by fwi))::numeric,1) p95,"
            + " round((percentile_cont(0.99) within group (order by fwi))::numeric,1) p99"
            + " from fwi_municipio where not calentamiento group by y order by y",
        rs ->
            md.append("| ")
                .append(rs.getInt("y"))
                .append(" | ")
                .append(rs.getBigDecimal("med"))
                .append(" | ")
                .append(rs.getBigDecimal("mx"))
                .append(" | ")
                .append(rs.getBigDecimal("p90"))
                .append(" | ")
                .append(rs.getBigDecimal("p95"))
                .append(" | ")
                .append(rs.getBigDecimal("p99"))
                .append(" |\n"));

    md.append("\n## b) Validación de eventos (percentil en ventana ±15 días del municipio)\n\n");
    md.append("| evento | fecha | FWI | percentil |\n|---|---|---|---|\n");
    for (Evento e : EVENTOS) {
      md.append("| ").append(e.nombre()).append(" | ").append(e.fecha()).append(" | ");
      Map<String, Object> r = percentilEvento(e);
      if (r == null || r.get("fwi_ev") == null) {
        md.append("— | sin dato en el rango |\n");
      } else {
        double pct = ((Number) r.get("pct")).doubleValue() * 100.0;
        md.append(String.format(java.util.Locale.ROOT, "%.1f", ((Number) r.get("fwi_ev"))))
            .append(" | ")
            .append(String.format(java.util.Locale.ROOT, "P%.1f", pct))
            .append(" |\n");
      }
    }

    md.append("\n## c) 6 municipios de control — FWI medio de julio\n\n");
    md.append("| municipio | FWI medio julio |\n|---|---|\n");
    CONTROL.forEach(
        (ine, nombre) -> {
          Double medio =
              jdbc.queryForObject(
                  "select round(avg(fwi),1) from fwi_municipio where ine_code = ?"
                      + " and extract(month from fecha) = 7 and not calentamiento",
                  Double.class,
                  ine);
          md.append("| ").append(nombre).append(" | ").append(medio).append(" |\n");
        });
    return md.toString();
  }

  private Map<String, Object> percentilEvento(Evento e) {
    int doy = e.fecha().getDayOfYear();
    List<Map<String, Object>> r =
        jdbc.queryForList(
            "with ev as (select fwi from fwi_municipio where ine_code = ? and fecha = ?),"
                + " vent as (select fwi from fwi_municipio where ine_code = ? and not calentamiento"
                + " and abs(((extract(doy from fecha)::int - ?) + 182) % 365 - 182) <= 15)"
                + " select (select fwi from ev) fwi_ev,"
                + " (select count(*) filter (where vent.fwi <= (select fwi from ev))::float"
                + " / nullif(count(*),0) from vent) pct",
            e.ineCode(),
            e.fecha(),
            e.ineCode(),
            doy);
    return r.isEmpty() ? null : r.get(0);
  }
}
