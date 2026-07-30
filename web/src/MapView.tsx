import { useEffect, useRef } from "react";
import maplibregl, { type StyleSpecification } from "maplibre-gl";
import { z } from "zod";
import "maplibre-gl/dist/maplibre-gl.css";

// Validación en el borde (convención del repo): el extent debe traer 4 números.
const ExtentSchema = z.object({
  bbox: z.tuple([z.number(), z.number(), z.number(), z.number()]),
});

// El visor pide /api al mismo origen: en dev lo reenvía Vite (proxy), en producción Apache/nginx.
const TILES = `${window.location.origin}/api/v1/tiles/municipios/{z}/{x}/{y}.mvt`;
const EXTENT_URL = `${window.location.origin}/api/v1/mapa/extent`;

// Atribución obligatoria, en el control de MapLibre (no en un aparte).
const ATTRIBUTION =
  '© <a href="https://www.ign.es" target="_blank" rel="noreferrer">IGN/CNIG</a> ' +
  "(límites municipales y MDT25, CC-BY 4.0) · " +
  '© <a href="https://agroambient.gva.es" target="_blank" rel="noreferrer">Generalitat ' +
  "Valenciana</a> (PATFOR)";

const SRC = "municipios";
const HOVER_ON: maplibregl.ExpressionSpecification = [
  "boolean",
  ["feature-state", "hover"],
  false,
];

// Estilo autocontenido: fondo liso + la capa de municipios. Sin fondo ráster de terceros, sin
// token de Mapbox ni servicios con clave (ADR-04 / requisitos de seguridad).
const STYLE: StyleSpecification = {
  version: 8,
  sources: {
    [SRC]: {
      type: "vector",
      tiles: [TILES],
      minzoom: 0,
      maxzoom: 16,
      // promoteId: la id de cada feature pasa a ser su ine_code. Imprescindible para que
      // setFeatureState (hover ahora, coropleto del índice en Fase 3) case por municipio.
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
      paint: {
        "fill-color": ["case", HOVER_ON, "#3b4a42", "#1c2420"],
        "fill-opacity": 0.9,
      },
    },
    {
      id: "municipios-borde",
      type: "line",
      source: SRC,
      "source-layer": SRC,
      paint: {
        "line-color": ["case", HOVER_ON, "#f59e0b", "#5a6b61"],
        "line-width": ["case", HOVER_ON, 2.5, 0.7],
      },
    },
  ],
};

function escapeHtml(s: string): string {
  const rep: Record<string, string> = {
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  };
  return s.replace(/[&<>"']/g, (c) => rep[c] ?? c);
}

export default function MapView() {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) {
      return;
    }
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: STYLE,
      center: [-0.2, 40.1],
      zoom: 7,
      attributionControl: false, // se añade abajo, no compacto, para que la atribución se vea
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.addControl(new maplibregl.AttributionControl({ compact: false }));

    // Encuadre inicial desde el extent CONTINENTAL (no la administrativa: esta arrancaría sobre
    // 50 km de mar por las Columbretes). Si falla, el visor sigue con el encuadre por defecto.
    fetch(EXTENT_URL)
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        const parsed = ExtentSchema.safeParse(data);
        if (!parsed.success) {
          return;
        }
        const [w, s, e, n] = parsed.data.bbox;
        map.fitBounds(
          [
            [w, s],
            [e, n],
          ],
          { padding: 24, duration: 0 },
        );
      })
      .catch(() => {});

    // Resalte del límite con feature-state (además valida que promoteId está bien puesto).
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

    // Clic en un término -> nombre y comarca.
    const popup = new maplibregl.Popup({ closeButton: true, closeOnClick: true });
    map.on("click", "municipios-relleno", (ev) => {
      const f = ev.features?.[0];
      if (!f) {
        return;
      }
      const nombre = escapeHtml(String(f.properties?.nombre ?? "—"));
      const comarca = escapeHtml(String(f.properties?.comarca ?? "—"));
      popup
        .setLngLat(ev.lngLat)
        .setHTML(`<strong>${nombre}</strong><br /><span class="pop-comarca">${comarca}</span>`)
        .addTo(map);
    });

    return () => map.remove();
  }, []);

  return <div ref={containerRef} className="map" />;
}
