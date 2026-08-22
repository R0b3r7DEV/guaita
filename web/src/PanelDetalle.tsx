import { useEffect, useState } from "react";
import { ApiError, detalleMunicipio, type Detalle } from "./api";
import { colorNivel, etiquetaNivel } from "./niveles";
import Serie30d from "./Serie30d";

type Estado =
  | { tipo: "cargando" }
  | { tipo: "ok"; d: Detalle }
  | { tipo: "obsoleto" }
  | { tipo: "error" };

/** Barra 0..100 de un componente (magnitud, un solo tono; no la paleta de niveles). */
function Componente({ nombre, valor }: { nombre: string; valor: number }) {
  return (
    <div className="comp">
      <div className="comp-cab">
        <span>{nombre}</span>
        <strong>{valor.toFixed(1)}</strong>
      </div>
      <div className="comp-barra">
        <div className="comp-relleno" style={{ width: `${Math.min(100, valor)}%` }} />
      </div>
    </div>
  );
}

export default function PanelDetalle({
  ineCode,
  onClose,
}: {
  ineCode: string;
  onClose: () => void;
}) {
  const [estado, setEstado] = useState<Estado>({ tipo: "cargando" });

  useEffect(() => {
    let vivo = true;
    setEstado({ tipo: "cargando" });
    detalleMunicipio(ineCode)
      .then((d) => vivo && setEstado({ tipo: "ok", d }))
      .catch((e) => {
        if (!vivo) {
          return;
        }
        setEstado({ tipo: e instanceof ApiError && e.status === 503 ? "obsoleto" : "error" });
      });
    return () => {
      vivo = false;
    };
  }, [ineCode]);

  return (
    <aside className="panel" aria-label="Detalle del municipio">
      <button className="panel-cerrar" onClick={onClose} aria-label="Cerrar">
        ×
      </button>
      {estado.tipo === "cargando" && <p className="panel-info">Cargando…</p>}
      {estado.tipo === "error" && <p className="panel-info">No se pudo cargar el detalle.</p>}
      {estado.tipo === "obsoleto" && (
        <p className="panel-info">Índice no disponible para este municipio todavía.</p>
      )}
      {estado.tipo === "ok" && <Contenido d={estado.d} />}
    </aside>
  );
}

function Contenido({ d }: { d: Detalle }) {
  const delta = d.calidadDato.deltaAltitudM;
  const sentido = delta >= 0 ? "por debajo de" : "por encima de";
  const sinComb = Math.round(d.calidadDato.fracSinCombustible * 100);

  return (
    <div className="panel-cuerpo">
      <header className="panel-cab">
        <h2>{d.nombre}</h2>
        <span className="panel-comarca">{d.comarca}</span>
        <div className="panel-nivel" style={{ background: colorNivel(d.nivel) }}>
          Nivel {d.nivel} · {etiquetaNivel(d.nivel)} · índice {d.indice.toFixed(1)}
        </div>
        <span className="panel-fecha">Dato del {d.fecha}</span>
      </header>

      <section>
        <h3>Componentes</h3>
        <Componente nombre="Meteo (percentil FWI)" valor={d.componentes.meteo} />
        <Componente nombre="Estructural (combustible)" valor={d.componentes.estructural} />
        <Componente nombre="Vulnerabilidad" valor={d.componentes.vulnerabilidad} />
        <p className="panel-nota">
          El índice es la media geométrica: si la meteo es baja (lluvia), el peligro cae aunque haya
          mucho combustible.
        </p>
      </section>

      <section>
        <h3>Índice · últimos 30 días</h3>
        <Serie30d puntos={d.serie30d} />
      </section>

      <section>
        <h3>Códigos FWI del día</h3>
        <div className="fwi-grid">
          <span>FFMC {d.fwi.ffmc.toFixed(1)}</span>
          <span>DMC {d.fwi.dmc.toFixed(1)}</span>
          <span>DC {d.fwi.dc.toFixed(1)}</span>
          <span>ISI {d.fwi.isi.toFixed(1)}</span>
          <span>BUI {d.fwi.bui.toFixed(1)}</span>
          <span>FWI {d.fwi.fwi.toFixed(1)}</span>
        </div>
      </section>

      <section>
        <h3>Banderas</h3>
        <p>
          <strong>Regla 30-30-30:</strong>{" "}
          {d.banderas.regla303030 ? "activa hoy" : "no activa hoy"}. Se enciende con temperatura ≥ 30
          °C, humedad ≤ 30 % y viento ≥ 30 km/h simultáneos: condiciones de propagación rápida.
        </p>
        <p>
          <strong>Viento alineado:</strong>{" "}
          {d.banderas.vientoAlineado === null
            ? "no disponible"
            : d.banderas.vientoAlineado
              ? "sí"
              : "no"}
          . Requiere dirección de viento respecto a la orografía, que aún no se captura en todo el
          histórico.
        </p>
      </section>

      <section>
        <h3>Calidad del dato</h3>
        <p className="panel-nota">
          La celda del modelo meteo está {Math.abs(delta).toFixed(0)} m {sentido} la altitud media
          del término (celda a {d.calidadDato.elevacionCeldaM.toFixed(0)} m). A mayor diferencia,
          menos fiable el ajuste por altitud.
        </p>
        <p className="panel-nota">
          {sinComb} % de la superficie forestal sin modelo de combustible asignado (se usó el peso
          por defecto).
        </p>
      </section>
    </div>
  );
}
