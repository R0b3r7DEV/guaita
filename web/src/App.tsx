/**
 * Fase 0: cáscara mínima. Solo confirma que el frontend arranca y muestra el
 * aviso permanente exigido por docs/00 y docs/07 (amenaza T7). El visor
 * MapLibre y los datos llegan en la Fase 1.
 */
function App() {
  return (
    <main className="app">
      {/* Aviso permanente: NO es un sistema de emergencia (docs/07, T7). */}
      <aside className="disclaimer" role="note" aria-label="Aviso importante">
        <strong>GUAITA no es un sistema de emergencias.</strong> No sustituye al
        112 ni al boletín oficial PREVIFOC de la Generalitat Valenciana. Ante un
        incendio, llame al <strong>112</strong>.
      </aside>

      <header className="hero">
        <h1>GUAITA</h1>
        <p className="tagline">
          Inteligencia de riesgo de incendio forestal · provincia de Castellón
        </p>
        <p className="phase">Fase 0 — cimientos. El visor llegará en la Fase 1.</p>
      </header>
    </main>
  );
}

export default App;
