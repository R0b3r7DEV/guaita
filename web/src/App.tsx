import { useState } from "react";
import MapView, { type MetaMapa } from "./MapView";
import Leyenda from "./Leyenda";
import PanelDetalle from "./PanelDetalle";
import PanelMetodologia from "./PanelMetodologia";

/**
 * Visor coropleto del índice de peligro (Fase 3). Geometría desde la tesela inmutable (ADR-04/06),
 * nivel unido por ine_code (feature-state). El aviso permanente de que NO es un sistema de
 * emergencias (docs/07, T7 —la amenaza más seria del proyecto—) va arriba, visible sin scroll.
 */
function App() {
  const [seleccion, setSeleccion] = useState<string | null>(null);
  const [meta, setMeta] = useState<MetaMapa>({ fecha: null, versionModelo: null, obsoleto: false });
  const [metodoAbierta, setMetodoAbierta] = useState(false);

  return (
    <div className="app">
      <aside className="disclaimer" role="note" aria-label="Aviso importante">
        <p>
          <strong>GUAITA no es un sistema de emergencias.</strong> No sustituye al 112 ni al boletín
          oficial PREVIFOC de la Generalitat Valenciana. Herramienta analítica de portafolio: el
          índice no es oficial. Ante un incendio, llame al <strong>112</strong>.
        </p>
        <p className="disclaimer-desfase">
          El índice refleja <strong>peligro real</strong> (no una anomalía), pero calculado sobre
          reanálisis meteorológico con unos <strong>5 días de desfase</strong>: un nivel alto
          describe las condiciones de hace días, <strong>no necesariamente las de hoy</strong>. Para
          el riesgo <strong>actual</strong>, consulte el{" "}
          <a
            href="https://www.112cv.gva.es/es/incendios-forestales"
            target="_blank"
            rel="noreferrer"
          >
            boletín de preemergencia por incendios de la Generalitat Valenciana (112 CV)
          </a>
          .
        </p>
      </aside>

      <div className="map-wrap">
        <MapView onSelect={setSeleccion} onMeta={setMeta} />

        <div className="brand">
          <strong>GUAITA</strong>
          <span>Riesgo de incendio forestal · Castellón</span>
          <button className="brand-metodo" onClick={() => setMetodoAbierta(true)}>
            Metodología y limitaciones
          </button>
        </div>

        {meta.obsoleto && (
          <div className="obsoleto" role="alert">
            Índice no disponible ahora mismo. El mapa no refleja el peligro actual.
          </div>
        )}

        <Leyenda fecha={meta.fecha} obsoleto={meta.obsoleto} />

        {seleccion && (
          <PanelDetalle ineCode={seleccion} onClose={() => setSeleccion(null)} />
        )}
      </div>

      {metodoAbierta && <PanelMetodologia onClose={() => setMetodoAbierta(false)} />}
    </div>
  );
}

export default App;
