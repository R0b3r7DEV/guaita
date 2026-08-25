import { useEffect, useState } from "react";
import { metodologia, type Metodologia } from "./api";

/**
 * Panel de metodología (docs/06): versión, fórmula, pesos y —sobre todo— los CAVEATS visibles, no
 * enterrados: pesos sin calibrar, f_tiempo incompleto sin EFFIS, vulnerabilidad provisional. La
 * transparencia es parte del producto.
 */
export default function PanelMetodologia({ onClose }: { onClose: () => void }) {
  const [m, setM] = useState<Metodologia | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let vivo = true;
    metodologia()
      .then((r) => vivo && setM(r))
      .catch(() => vivo && setError(true));
    return () => {
      vivo = false;
    };
  }, []);

  return (
    <div className="modal-fondo" role="dialog" aria-modal="true" aria-label="Metodología">
      <div className="modal">
        <button className="panel-cerrar" onClick={onClose} aria-label="Cerrar">
          ×
        </button>
        {error && <p className="panel-info">No se pudo cargar la metodología.</p>}
        {m && (
          <div className="modal-cuerpo">
            <h2>Metodología · modelo {m.versionModelo}</h2>
            <p className="metodo-formula">{m.formula}</p>

            <h3>Forma vigente</h3>
            <ul className="metodo-lista">
              <li>
                <strong>Meteo</strong> (base): percentil del FWI sobre la distribución provincial
                completa. Absoluta, no anomalía estacional: conserva la magnitud del verano.
              </li>
              <li>
                <strong>Estructura</strong> (modulador): banda acotada [{m.modulador.min}–
                {m.modulador.max}], pendiente {m.modulador.pendiente} anclada en{" "}
                {m.modulador.anclaje} (mediana provincial). Solo mueve a los extremos.
              </li>
              <li>
                <strong>Vulnerabilidad</strong>: exposición, fuera del índice (normalización de
                población: {m.normaPoblacion}). Se muestra como contexto.
              </li>
              <li>Niveles (topes): {m.niveles.join(" · ")}</li>
            </ul>

            <h3>Limitaciones conocidas</h3>
            <ul className="metodo-caveats">
              {m.caveats.map((c) => (
                <li key={c}>{c}</li>
              ))}
            </ul>

            <a href={m.documentacion} target="_blank" rel="noreferrer" className="metodo-doc">
              Documentación técnica completa (docs/04) →
            </a>
          </div>
        )}
      </div>
    </div>
  );
}
