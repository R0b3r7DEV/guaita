package dev.r0b3r7.guaita.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guardia de disco del backfill. El backfill escribe durante horas; en un VPS que comparte disco
 * con producción (XPL0DAY), llenar el disco tumbaría TODA la máquina, no solo GUAITA — es el único
 * fallo capaz de dañar algo ajeno al proyecto. Por eso comprueba el espacio libre en la ruta
 * vigilada (en el VPS, un bind READ-ONLY del disco del host, no el efímero del contenedor) antes de
 * arrancar y antes de cada año, y ABORTA LIMPIAMENTE si baja del umbral: lanza ANTES de abrir la
 * transacción del año, así lo ya escrito queda commiteado y consistente y el log dice dónde
 * reanudar. No es un kill a medias.
 */
@Component
public class DiskGuard {

  private static final Logger log = LoggerFactory.getLogger(DiskGuard.class);
  private static final long GB = 1024L * 1024L * 1024L;
  private static final long MB = 1024L * 1024L;

  private final Path path;
  private final long minFreeBytes;
  private final long bytesPorMunicipioAño;

  public DiskGuard(
      @Value("${guaita.backfill.disk.guard-path:.}") String guardPath,
      @Value("${guaita.backfill.disk.min-free-gb:5}") long minFreeGb,
      @Value("${guaita.backfill.disk.bytes-por-municipio-anio:262144}") long bytesPorMunicipioAño) {
    this.path = Path.of(guardPath);
    this.minFreeBytes = minFreeGb * GB;
    this.bytesPorMunicipioAño = bytesPorMunicipioAño;
  }

  /** Espacio utilizable (bytes) en la ruta vigilada. */
  public long libresBytes() {
    try {
      return Files.getFileStore(path).getUsableSpace();
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer el espacio libre de " + path, e);
    }
  }

  /** Estimación (bytes) de lo que ocupará un rango de años para n municipios. */
  public long estimaBytes(int desde, int hasta, int municipios) {
    long añosMunicipio = (long) Math.max(0, hasta - desde + 1) * municipios;
    return añosMunicipio * bytesPorMunicipioAño;
  }

  /**
   * Comprobación de arranque: imprime el libre actual y la estimación del tramo, y se NIEGA a
   * empezar si la estimación no cabe dejando el margen mínimo libre. Lanza antes de escribir nada.
   */
  public void verificarAntesDeArrancar(int desde, int hasta, int municipios) {
    long libres = libresBytes();
    long estima = estimaBytes(desde, hasta, municipios);
    log.info(
        "guardia de disco en {}: libres {} GB, estimación del tramo {} MB, margen mínimo {} GB",
        path.toAbsolutePath(), libres / GB, estima / MB, minFreeBytes / GB);
    if (libres < estima + minFreeBytes) {
      throw new IllegalStateException(
          "disco insuficiente para el tramo "
              + desde
              + ".."
              + hasta
              + ": libres "
              + (libres / GB)
              + " GB < estimación "
              + (estima / MB)
              + " MB + margen "
              + (minFreeBytes / GB)
              + " GB. No se arranca.");
    }
  }

  /**
   * Comprobación durante la ejecución (antes de cada año). Aborta limpiamente si el libre baja del
   * umbral: lanza ANTES de abrir la transacción del año, dejando lo ya escrito commiteado y el
   * punto de reanudación en el log.
   */
  public void verificarAntesDeCadaAño(int año) {
    long libres = libresBytes();
    if (libres < minFreeBytes) {
      log.error(
          "GUARDIA DE DISCO: libres {} GB < umbral {} GB antes del año {}."
              + " Aborto limpio; reanuda desde {} cuando haya espacio.",
          libres / GB,
          minFreeBytes / GB,
          año,
          año);
      throw new IllegalStateException(
          "guardia de disco: espacio bajo el umbral antes del año " + año + " (reanudable)");
    }
  }
}
