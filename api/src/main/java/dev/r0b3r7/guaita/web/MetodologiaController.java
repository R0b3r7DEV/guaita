package dev.r0b3r7.guaita.web;

import dev.r0b3r7.guaita.risk.ModeloParams;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metodología del índice (docs/06). La transparencia es parte del producto: versión del modelo,
 * pesos vigentes, normalización, niveles y las LIMITACIONES conocidas (pesos sin calibrar, f_tiempo
 * incompleto sin EFFIS, vulnerabilidad provisional). Se sirve desde {@link ModeloParams} (config),
 * no desde la BD; cambia solo al subir de versión, así que cachea un día.
 */
@RestController
class MetodologiaController {

  private static final List<String> ETIQUETAS =
      List.of("Bajo", "Moderado", "Alto", "Muy alto", "Extremo");

  private static final List<String> CAVEATS =
      List.of(
          "Pesos de combustible DE PARTIDA (comportamiento publicado de Anderson), sin calibrar aún;"
              + " calibración en Fase 4.",
          "f_tiempo (regeneración) incompleto sin los perímetros de EFFIS: solo los incendios semilla"
              + " reducen el combustible; el resto queda neutro (f_tiempo=1.0).",
          "comp_vulnerabilidad PROVISIONAL (población normalizada + suelo protegido); es un proxy"
              + " débil, se sustituye en v2.0 con el módulo IUF/WUI.");

  private static final String DOCUMENTACION =
      "https://github.com/R0b3r7DEV/guaita/blob/main/docs/04-indice-peligro.md";

  /** Pesos vigentes del índice y sus componentes. */
  record Pesos(double estructural, double vulnerab, double poblacion, double espacioProtegido) {}

  /** Respuesta de {@code /metodologia}. */
  record Metodologia(
      String versionModelo,
      String formula,
      Pesos pesos,
      String normaPoblacion,
      int meteoVentanaDias,
      List<Integer> niveles,
      List<String> etiquetasNivel,
      List<String> caveats,
      String documentacion,
      String aviso) {}

  private final ModeloParams params;

  MetodologiaController(ModeloParams params) {
    this.params = params;
  }

  @GetMapping("/api/v1/metodologia")
  ResponseEntity<Metodologia> metodologia() {
    Metodologia m =
        new Metodologia(
            params.version(),
            "indice = comp_meteo^0.5 * (peso_estructural*comp_estructural"
                + " + peso_vulnerab*comp_vulnerab)^0.5",
            new Pesos(
                params.indice().pesoEstructural(),
                params.indice().pesoVulnerab(),
                params.vulnerab().pesoPoblacion(),
                params.vulnerab().pesoEspacioProtegido()),
            params.vulnerab().normaPoblacion().name().toLowerCase(),
            params.meteo().ventanaDias(),
            params.indice().niveles(),
            ETIQUETAS,
            CAVEATS,
            DOCUMENTACION,
            Avisos.EMERGENCIAS);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
        .body(m);
  }
}
