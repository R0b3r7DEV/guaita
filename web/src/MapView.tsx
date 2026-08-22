import { useEffect, useRef } from "react";
import maplibregl, { type StyleSpecification } from "maplibre-gl";
import { z } from "zod";
import "maplibre-gl/dist/maplibre-gl.css";
import { listaMunicipios } from "./api";
import { NIVELES, SIN_DATO } from "./niveles";

const ExtentSchema = z.object({
  bbox: z.tuple([z.number(), z.number(), z.number(), z.number()]),
});

const TILES = `${window.location.origin}/api/v1/tiles/municipios/{z}/{x}/{y}.mvt`;
const EXTENT_URL = `${window.location.origin}/api/v1/mapa/extent`;

// Atribución obligatoria por fuente (docs/07), en el control de MapLibre.
const ATTRIBUTION = [
  '© <a href="https://www.ign.es" target="_blank" rel="noreferrer">IGN/CNIG</a>' +
    " (límites y MDT25, CC-BY 4.0)",
  '© <a href="https://agroambient.gva.es" target="_blank" rel="noreferrer">GVA</a> (PATFOR)',
  '© <a href="https://www.miteco.gob.es" target="_blank" rel="noreferrer">MITECO</a>' +
    " (Red Natura 2000 / ENP)",
  '© <a href="https://open-meteo.com" target="_blank" rel="noreferrer">Open-Meteo</a>;' +
    " contains modified Copernicus Climate Change Service information (ERA5)",
].join(" · ");

const SRC = "municipios";
const HOVER_ON: maplibregl.ExpressionSpecification = ["boolean", ["feature-state", "hover"], false];

// Coropleta por NIVEL vía feature-state (unido por ine_code sobre la tesela inmutable, ADR-06):
// cinco clases discretas, no un índice continuo. Sin nivel (sin dato) -> gris.
const FILL_COLOR: maplibregl.ExpressionSpecification = [
  "match",
  ["to-number", ["feature-state", "nivel"], 0],
  NIVELES[0].nivel,
  NIVELES[0].color,
  NIVELES[1].nivel,
  NIVELES[1].color,
  NIVELES[2].nivel,
  NIVELES[2].color,
  NIVELES[3].nivel,
  NIVELES[3].color,
  NIVELES[4].nivel,
  NIVELES[4].color,
  SIN_DATO,
];

const STYLE: StyleSpecification = {
  version: 8,
  sources: {
    [SRC]: {
      type: "vector",
      tiles: [TILES],
      minzoom: 0,
      maxzoom: 16,
      promoteId: "ine_code",
      attribution: ATTRIBUTION,
    },
  },
  layers: [
    { id: "fondo", type: "background", paint: { "background-color": "#0f1211" } },
    {
      id: "municipios-relleno",
      type: "fill",
      source: SRC,
      "source-layer": SRC,
      paint: { "fill-color": FILL_COLOR, "fill-opacity": 0.85 },
    },
    {
      id: "municipios-borde",
      type: "line",
      source: SRC,
      "source-layer": SRC,
      paint: {
        "line-color": ["case", HOVER_ON, "#ffffff", "#0f1211"],
        "line-width": ["case", HOVER_ON, 2.2, 0.6],
      },
    },
  ],
};

export interface MetaMapa {
  fecha: string | null;
  versionModelo: string | null;
  obsoleto: boolean;
}

interface Props {
  onSelect: (ineCode: string) => void;
  onMeta: (meta: MetaMapa) => void;
}

export default function MapView({ onSelect, onMeta }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const onSelectRef = useRef(onSelect);
  const onMetaRef = useRef(onMeta);
  onSelectRef.current = onSelect;
  onMetaRef.current = onMeta;

  useEffect(() => {
    if (!containerRef.current) {
      return;
    }
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: STYLE,
      center: [-0.2, 40.1],
      zoom: 7,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.addControl(new maplibregl.AttributionControl({ compact: false }));

    fetch(EXTENT_URL)
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        const parsed = ExtentSchema.safeParse(data);
        if (parsed.success) {
          const [w, s, e, n] = parsed.data.bbox;
          map.fitBounds(
            [
              [w, s],
              [e, n],
            ],
            { padding: 24, duration: 0 },
          );
        }
      })
      .catch(() => {});

    // Nivel por municipio; se aplica como feature-state y se RE-aplica cuando entran teselas nuevas
    // (otro zoom), porque el estado solo pinta features ya cargadas.
    const nivelPorIne = new Map<string, number>();
    const aplicarEstados = () => {
      for (const [ine, nivel] of nivelPorIne) {
        map.setFeatureState({ source: SRC, sourceLayer: SRC, id: ine }, { nivel });
      }
    };

    listaMunicipios()
      .then((lista) => {
        for (const m of lista.data) {
          nivelPorIne.set(m.ineCode, m.nivel);
        }
        aplicarEstados();
        onMetaRef.current({
          fecha: lista.meta.fecha,
          versionModelo: lista.meta.versionModelo,
          obsoleto: false,
        });
      })
      .catch(() => {
        // 503 u otro fallo: el mapa queda en gris (sin dato) y se avisa; NUNCA se inventan niveles.
        onMetaRef.current({ fecha: null, versionModelo: null, obsoleto: true });
      });

    map.on("sourcedata", (e) => {
      if (e.sourceId === SRC && e.isSourceLoaded) {
        aplicarEstados();
      }
    });

    let hovered: string | number | undefined;
    const clearHover = () => {
      if (hovered !== undefined) {
        map.setFeatureState({ source: SRC, sourceLayer: SRC, id: hovered }, { hover: false });
        hovered = undefined;
      }
    };
    map.on("mousemove", "municipios-relleno", (ev) => {
      map.getCanvas().style.cursor = "pointer";
      const f = ev.features?.[0];
      if (f?.id === undefined || f.id === hovered) {
        return;
      }
      clearHover();
      hovered = f.id;
      map.setFeatureState({ source: SRC, sourceLayer: SRC, id: hovered }, { hover: true });
    });
    map.on("mouseleave", "municipios-relleno", () => {
      map.getCanvas().style.cursor = "";
      clearHover();
    });

    map.on("click", "municipios-relleno", (ev) => {
      const f = ev.features?.[0];
      if (f?.id !== undefined) {
        onSelectRef.current(String(f.id));
      }
    });

    return () => map.remove();
  }, []);

  return <div ref={containerRef} className="map" />;
}
