package dev.r0b3r7.guaita.ingest;

import dev.r0b3r7.guaita.risk.FwiCodes;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia de {@code fwi_municipio}. Clave para reanudar: {@link #estado} lee el estado
 * recursivo (FFMC/DMC/DC) de un día ya calculado y {@link #ultimaFecha} da el punto por el que
 * retomar la cadena. El backfill NUNCA reinicia con los valores de arranque salvo el primer día
 * absoluto de la serie (docs/04 §1); leer de aquí es lo que lo impide.
 */
@Repository
public class FwiMunicipioRepository {

  /** Estado recursivo de un día: lo único que necesita el día siguiente. */
  public record Estado(double ffmc, double dmc, double dc) {}

  private static final String UPSERT_SQL =
      """
      insert into fwi_municipio
        (ine_code, fecha, ffmc, dmc, dc, isi, bui, fwi, calentamiento)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (ine_code, fecha) do update set
        ffmc = excluded.ffmc, dmc = excluded.dmc, dc = excluded.dc,
        isi = excluded.isi, bui = excluded.bui, fwi = excluded.fwi,
        calentamiento = excluded.calentamiento, calculado_en = now()
      """;

  private final JdbcTemplate jdbc;

  public FwiMunicipioRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Estado recursivo (FFMC/DMC/DC) del municipio en una fecha ya calculada, si existe. */
  public Optional<Estado> estado(String ineCode, LocalDate fecha) {
    List<Estado> r =
        jdbc.query(
            "select ffmc, dmc, dc from fwi_municipio where ine_code = ? and fecha = ?",
            (rs, n) -> new Estado(rs.getDouble("ffmc"), rs.getDouble("dmc"), rs.getDouble("dc")),
            ineCode,
            fecha);
    return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
  }

  /** Última fecha ya calculada del municipio (por la que reanudar), o {@code null} si ninguna. */
  public LocalDate ultimaFecha(String ineCode) {
    return jdbc.queryForObject(
        "select max(fecha) from fwi_municipio where ine_code = ?", LocalDate.class, ineCode);
  }

  /** Inserta o actualiza (idempotente) el FWI de un día. */
  public void upsert(String ineCode, LocalDate fecha, FwiCodes c, boolean calentamiento) {
    jdbc.update(
        UPSERT_SQL,
        ineCode,
        fecha,
        c.ffmc(),
        c.dmc(),
        c.dc(),
        c.isi(),
        c.bui(),
        c.fwi(),
        calentamiento);
  }
}
