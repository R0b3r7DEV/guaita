import type { PuntoSerie } from "./api";
import { colorNivel } from "./niveles";

const MIN_PUNTOS = 7; // por debajo, la gráfica no cuenta nada honesto: se avisa
const W = 300;
const H = 84;
const PAD = 6;

/**
 * Serie del índice de los últimos 30 días. Con menos de 7 puntos NO se pinta una gráfica con un
 * hueco raro: se muestran los puntos que hay con un aviso explícito de "serie en construcción"
 * —honesto sobre el estado real del sistema mientras se acumula el histórico del índice—.
 */
export default function Serie30d({ puntos }: { puntos: PuntoSerie[] }) {
  if (puntos.length === 0) {
    return <p className="serie-vacia">Sin serie todavía.</p>;
  }

  const xs = (i: number) =>
    PAD + (i * (W - 2 * PAD)) / Math.max(1, puntos.length - 1);
  const ys = (v: number) => H - PAD - (v / 100) * (H - 2 * PAD);
  const linea = puntos.map((p, i) => `${xs(i)},${ys(p.indice)}`).join(" ");

  return (
    <div>
      {puntos.length < MIN_PUNTOS && (
        <p className="serie-aviso">
          Serie en construcción ({puntos.length}/30 días). El histórico del índice se acumula día a
          día.
        </p>
      )}
      <svg
        className="serie-svg"
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`Índice de los últimos ${puntos.length} días`}
      >
        <line x1={PAD} y1={ys(0)} x2={W - PAD} y2={ys(0)} className="serie-eje" />
        <polyline points={linea} className="serie-linea" />
        {puntos.map((p, i) => (
          <circle key={p.fecha} cx={xs(i)} cy={ys(p.indice)} r={3} fill={colorNivel(p.indice > 0 ? nivelDe(p.indice) : 1)} />
        ))}
      </svg>
      <div className="serie-pie">
        <span>{puntos[0].fecha}</span>
        <span>{puntos[puntos.length - 1].fecha}</span>
      </div>
    </div>
  );
}

// Nivel aproximado a partir del índice, solo para colorear el punto (límites de docs/04).
function nivelDe(indice: number): number {
  const topes = [20, 40, 60, 80, 100];
  return topes.findIndex((t) => indice <= t) + 1 || 5;
}
