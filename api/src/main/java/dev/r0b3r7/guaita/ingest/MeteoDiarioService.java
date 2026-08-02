package dev.r0b3r7.guaita.ingest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pasada de ingesta diaria de meteo + FWI (operación, tras el backfill histórico). La dispara {@link
 * MeteoScheduler}, pero la lógica vive aquí para poder probarla sin el reloj.
 *
 * <p><b>Recuperación de huecos</b>: NO procesa "hoy". Ingiere desde la última fecha del municipio
 * más retrasado + 1 hasta el corte del archivo (~D-5); si el servidor estuvo caído una semana,
 * recupera los siete días. Como el FWI es recursivo, un hueco no recuperado rompería la cadena para
 * siempre. <b>Fallo de fuente</b>: si Open-Meteo cae o el lote no pasa validación, la petición lanza
 * ANTES de escribir nada — no se persisten filas ni ceros; el hueco queda y la pasada siguiente lo
 * recupera. Un FWI en cero sin explicación es peligroso en esta aplicación. Idempotente: una segunda
 * pasada seguida no encuentra nada que ingerir.
 */
@Service
public class MeteoDiarioService {

  private static final Logger log = LoggerFactory.getLogger(MeteoDiarioService.class);

  /** Resumen de una pasada (para el log estructurado y la métrica de retraso). */
  public record Resultado(
      LocalDate desde,
      LocalDate hasta,
      int municipios,
      int diasMeteo,
      int fwiActualizado,
      long diasRetraso) {}

  private final MeteoMunicipioRepository meteoRepo;
  private final BackfillService backfill;
  private final OpenMeteoClient client;

  public MeteoDiarioService(
      MeteoMunicipioRepository meteoRepo, BackfillService backfill, OpenMeteoClient client) {
    this.meteoRepo = meteoRepo;
    this.backfill = backfill;
    this.client = client;
  }

  /** Ejecuta una pasada: ingiere meteo pendiente, calcula FWI y devuelve el resumen. */
  public Resultado pasada() {
    List<PuntoMeteo> base = meteoRepo.puntosDeConsulta();
    if (base.isEmpty()) {
      throw new IllegalStateException("no hay municipios/topografía sembrados: ejecuta make seed");
    }
    LocalDate corte = client.corteArchivo(base.get(0));
    LocalDate ultima = meteoRepo.ultimaFechaMinima();
    LocalDate desde = ultima == null ? BackfillService.SERIE_INICIO : ultima.plusDays(1);
    if (desde.isAfter(corte)) {
      log.info("meteo al día (última {} , corte {}); nada que ingerir", ultima, corte);
      return new Resultado(desde, corte, base.size(), 0, 0, 0);
    }

    // Fallo de fuente: elevaciones y fetch pueden lanzar. Están ANTES del upsert, así que si algo
    // falla no se ha escrito nada; el hueco queda y la próxima pasada lo recupera.
    Map<String, Double> nativas = client.elevacionesNativas(base, corte);
    List<PuntoMeteo> puntos = conElevaciones(base, nativas);
    List<MeteoMunicipio> filas = client.fetch(puntos, desde, corte);

    meteoRepo.upsertAll(filas);
    int fwi = 0;
    for (PuntoMeteo p : puntos) {
      fwi += backfill.computeFwiMunicipio(p.ineCode());
    }

    LocalDate max = meteoRepo.ultimaFechaMaxima();
    long retraso = max == null ? 0 : ChronoUnit.DAYS.between(max, corte);
    log.info(
        "pasada: meteo [{}..{}] {} municipios, {} filas, {} FWI; retraso {} d",
        desde,
        corte,
        puntos.size(),
        filas.size(),
        fwi,
        retraso);
    return new Resultado(desde, corte, puntos.size(), filas.size(), fwi, retraso);
  }

  private static List<PuntoMeteo> conElevaciones(List<PuntoMeteo> base, Map<String, Double> nativas) {
    List<PuntoMeteo> puntos = new ArrayList<>(base.size());
    for (PuntoMeteo p : base) {
      puntos.add(
          new PuntoMeteo(
              p.ineCode(), p.lon(), p.lat(), p.altitudMediaM(), nativas.get(p.ineCode())));
    }
    return puntos;
  }
}
