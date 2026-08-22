import { NIVELES, SIN_DATO } from "./niveles";

/**
 * Leyenda del coropleto. Muestra SIEMPRE las cinco clases aunque hoy alguna esté vacía (si no,
 * parecería que el sistema solo tiene tres niveles) y la fecha del dato, visible sin buscarla.
 */
export default function Leyenda({
  fecha,
  obsoleto,
}: {
  fecha: string | null;
  obsoleto: boolean;
}) {
  return (
    <div className="leyenda" aria-label="Leyenda del nivel de peligro">
      <div className="leyenda-cab">
        <strong>Nivel de peligro</strong>
        <span className="leyenda-fecha">
          {obsoleto || !fecha ? "sin dato disponible" : `dato del ${fecha}`}
        </span>
      </div>
      <ul>
        {NIVELES.map((n) => (
          <li key={n.nivel}>
            <span className="leyenda-caja" style={{ background: n.color }} />
            <span className="leyenda-txt">
              {n.etiqueta} <span className="leyenda-rango">({n.rango})</span>
            </span>
          </li>
        ))}
        <li>
          <span className="leyenda-caja" style={{ background: SIN_DATO }} />
          <span className="leyenda-txt">Sin dato</span>
        </li>
      </ul>
    </div>
  );
}
