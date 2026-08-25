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
          "El índice compuesto original (v1.0) NO batía al FWI crudo en la validación histórica."
              + " v1.1 usa la meteo (percentil del FWI provincial) como BASE y la estructura solo"
              + " como MODULADOR acotado (±15 %).",
          "La pendiente del modulador se DERIVA del efecto de la estructura sobre el TAMAÑO del"
              + " incendio (correlación con la superficie), NO se ajusta contra la ignición: con 15"
              + " positivos sería sobreajuste.",
          "Pesos de combustible DE PARTIDA (comportamiento publicado de Anderson), sin calibrar.",
          "comp_vulnerabilidad NO entra en el índice: mide EXPOSICIÓN («qué se pierde si arde»), no"
              + " peligro; va aparte como contexto. Proxy débil de población, se sustituye en v2.0"
              + " con el módulo IUF/WUI.",
          "Con meteo absoluta el índice ya es interpretable como PELIGRO real: un mapa en rojo de"
              + " hace días parece operativo, pero los datos llevan desfase. Ver el aviso.");

  private static final String DOCUMENTACION =
      "https://github.com/R0b3r7DEV/guaita/blob/main/docs/04-indice-peligro.md";

  /** Modulador estructural v1.1: banda acotada, pendiente derivada del tamaño. */
  record Modulador(double anclaje, double pendiente, double min, double max) {}

  /** Respuesta de {@code /metodologia}. */
  record Metodologia(
      String versionModelo,
      String formula,
      Modulador modulador,
      String normaPoblacion,
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
    ModeloParams.Indice i = params.indice();
    Metodologia m =
        new Metodologia(
            params.version(),
            "indice = comp_meteo_abs · clip(1 + pendiente·(comp_estructural − anclaje), min, max)",
            new Modulador(
                i.moduladorAnclaje(), i.moduladorPendiente(), i.moduladorMin(), i.moduladorMax()),
            params.vulnerab().normaPoblacion().name().toLowerCase(),
            i.niveles(),
            ETIQUETAS,
            CAVEATS,
            DOCUMENTACION,
            Avisos.EMERGENCIAS);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
        .body(m);
  }
}
