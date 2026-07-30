import MapView from "./MapView";

/**
 * Fase 1: el visor de los 135 términos de la provincia. Geometría e identidad desde el endpoint
 * MVT (ADR-04); el índice de peligro y el coropleto llegan en la Fase 3. El aviso permanente de que
 * NO es un sistema de emergencias (docs/07, T7) va arriba, visible sin scroll.
 */
function App() {
  return (
    <div className="app">
      {/* Aviso permanente: NO es un sistema de emergencia (docs/07, T7). */}
      <aside className="disclaimer" role="note" aria-label="Aviso importante">
        <strong>GUAITA no es un sistema de emergencias.</strong> No sustituye al 112 ni al boletín
        oficial PREVIFOC de la Generalitat Valenciana. Ante un incendio, llame al{" "}
        <strong>112</strong>.
      </aside>

      <div className="map-wrap">
        <MapView />
        <div className="brand">
          <strong>GUAITA</strong>
          <span>Riesgo de incendio forestal · Castellón</span>
        </div>
      </div>
    </div>
  );
}

export default App;
