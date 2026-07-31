package dev.r0b3r7.guaita.ingest;

import dev.r0b3r7.guaita.risk.FwiCalculator;
import dev.r0b3r7.guaita.risk.FwiCodes;
import dev.r0b3r7.guaita.risk.FwiWeather;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfill histórico de meteo y FWI (Fase 2, docs/04 §1). La meteo se descarga por años (una
 * petición multi-coord por año) y el FWI se calcula sobre la serie CONTINUA, todo reanudable.
 *
 * <p><b>La trampa del backfill</b> (recursión + reanudabilidad): si al retomar un tramo se reinicia
 * con los valores de arranque (85/6/15) en vez de leer el estado del día anterior desde {@code
 * fwi_municipio}, el DC se resetea a mitad de verano y la serie sigue pareciendo normal. Por eso
 * {@link #computeFwiMunicipio} SIEMPRE lee el estado previo de la BD y solo arranca con los valores
 * de arranque el primer día absoluto de la serie ({@link #SERIE_INICIO}).
 */
@Service
public class BackfillService {

  /** Primer día absoluto de la serie: el único que arranca con 85/6/15 (docs/04 §1). */
  public static final LocalDate SERIE_INICIO = LocalDate.of(2005, 1, 1);

  private static final int CALENTAMIENTO_DIAS = 30;

  private record MeteoDia(LocalDate fecha, double temp, double hr, double viento, double precip) {}

  private final JdbcTemplate jdbc;
  private final MeteoMunicipioRepository meteoRepo;
  private final FwiMunicipioRepository fwiRepo;
  private final OpenMeteoClient client;
  private final FwiCalculator fwi = FwiCalculator.canada();

  public BackfillService(
      JdbcTemplate jdbc,
      MeteoMunicipioRepository meteoRepo,
      FwiMunicipioRepository fwiRepo,
      OpenMeteoClient client) {
    this.jdbc = jdbc;
    this.meteoRepo = meteoRepo;
    this.fwiRepo = fwiRepo;
    this.client = client;
  }

  /**
   * Descarga la meteo de un año (recortado al último día del archivo) para todos los puntos y la
   * persiste. Idempotente. Una petición multi-coord por año (135 municipios caben, verificado).
   */
  @Transactional
  public int backfillMeteoAño(List<PuntoMeteo> puntos, int año, LocalDate corteArchivo) {
    LocalDate inicio = LocalDate.of(año, 1, 1);
    if (inicio.isAfter(corteArchivo)) {
      return 0;
    }
    LocalDate fin = LocalDate.of(año, 12, 31);
    if (fin.isAfter(corteArchivo)) {
      fin = corteArchivo;
    }
    List<MeteoMunicipio> filas = client.fetch(puntos, inicio, fin);
    meteoRepo.upsertAll(filas);
    return filas.size();
  }

  /**
   * Calcula el FWI de la serie continua de un municipio, REANUDABLE. Lee el estado previo de la BD;
   * solo arranca con 85/6/15 si {@code fwi_municipio} está vacío para el municipio. Marca {@code
   * calentamiento} los 30 primeros días de la serie. Exige serie meteo consecutiva: un hueco aborta
   * con error, no en silencio.
   */
  @Transactional
  public int computeFwiMunicipio(String ineCode) {
    LocalDate ultima = fwiRepo.ultimaFecha(ineCode);
    LocalDate desde = ultima == null ? SERIE_INICIO : ultima.plusDays(1);
    List<MeteoDia> serie = leerMeteoDesde(ineCode, desde);
    if (serie.isEmpty()) {
      return 0;
    }
    double f;
    double p;
    double d;
    LocalDate previa;
    if (ultima == null) {
      f = FwiCalculator.STARTUP_FFMC;
      p = FwiCalculator.STARTUP_DMC;
      d = FwiCalculator.STARTUP_DC;
      previa = serie.get(0).fecha().minusDays(1); // el primer día no comprueba continuidad
    } else {
      FwiMunicipioRepository.Estado e =
          fwiRepo
              .estado(ineCode, ultima)
              .orElseThrow(() -> new IllegalStateException("hueco: falta el estado en " + ultima));
      f = e.ffmc();
      p = e.dmc();
      d = e.dc();
      previa = ultima;
    }
    LocalDate finCalentamiento = SERIE_INICIO.plusDays(CALENTAMIENTO_DIAS - 1);
    int n = 0;
    for (MeteoDia m : serie) {
      if (!m.fecha().equals(previa.plusDays(1))) {
        throw new IllegalStateException(
            "hueco en la serie meteo de " + ineCode + ": tras " + previa + " viene " + m.fecha());
      }
      FwiWeather w = new FwiWeather(m.temp(), m.hr(), m.viento(), m.precip());
      FwiCodes c = fwi.step(f, p, d, m.fecha().getMonthValue(), w);
      fwiRepo.upsert(ineCode, m.fecha(), c, !m.fecha().isAfter(finCalentamiento));
      f = c.ffmc();
      p = c.dmc();
      d = c.dc();
      previa = m.fecha();
      n++;
    }
    return n;
  }

  private List<MeteoDia> leerMeteoDesde(String ineCode, LocalDate desde) {
    return jdbc.query(
        "select fecha, temp_12utc_c, hr_12utc_pct, viento_12utc_kmh, precip_24h_mm"
            + " from meteo_municipio where ine_code = ? and fecha >= ? order by fecha",
        (rs, n) ->
            new MeteoDia(
                rs.getObject("fecha", LocalDate.class),
                rs.getDouble("temp_12utc_c"),
                rs.getDouble("hr_12utc_pct"),
                rs.getDouble("viento_12utc_kmh"),
                rs.getDouble("precip_24h_mm")),
        ineCode,
        desde);
  }
}
