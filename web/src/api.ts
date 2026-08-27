import { z } from "zod";

// Validación en el borde (convención del repo): la API es la frontera, no confiamos en su forma.
const ORIGIN = window.location.origin;

const Banderas = z.object({
  regla303030: z.boolean(),
  vientoAlineado: z.boolean().nullable(), // null = sin dato de dirección; NUNCA se asume false
});

const MetaLista = z.object({
  fecha: z.string().nullable(),
  versionModelo: z.string(),
  aviso: z.string(),
});

const MunicipioResumen = z.object({
  ineCode: z.string(),
  nombre: z.string(),
  comarca: z.string(),
  indice: z.number(),
  nivel: z.number().int().min(1).max(5),
  fecha: z.string(),
  banderas: Banderas,
});

const Lista = z.object({ data: z.array(MunicipioResumen), meta: MetaLista });

const PuntoSerie = z.object({
  fecha: z.string(),
  indice: z.number(),
  fwi: z.number(),
});

const Detalle = z.object({
  ineCode: z.string(),
  nombre: z.string(),
  comarca: z.string(),
  fecha: z.string(),
  indice: z.number(),
  nivel: z.number().int().min(1).max(5),
  componentes: z.object({
    meteo: z.number(),
    estructural: z.number(),
    vulnerabilidad: z.number(),
  }),
  fwi: z.object({
    ffmc: z.number(),
    dmc: z.number(),
    dc: z.number(),
    isi: z.number(),
    bui: z.number(),
    fwi: z.number(),
  }),
  banderas: Banderas,
  calidadDato: z.object({
    deltaAltitudM: z.number(),
    elevacionCeldaM: z.number(),
    fracSinCombustible: z.number(),
  }),
  serie30d: z.array(PuntoSerie),
  meta: z.object({ fecha: z.string(), versionModelo: z.string(), aviso: z.string() }),
});

const Metodologia = z.object({
  versionModelo: z.string(),
  formula: z.string(),
  modulador: z.object({
    anclaje: z.number(),
    pendiente: z.number(),
    min: z.number(),
    max: z.number(),
  }),
  normaPoblacion: z.string(),
  exposicion: z.string(),
  niveles: z.array(z.number()),
  etiquetasNivel: z.array(z.string()),
  caveats: z.array(z.string()),
  documentacion: z.string(),
  aviso: z.string(),
});

// Exposición basada en interfaz real (IUF, docs/05). Agregado PÚBLICO: recuentos por clase. El
// detalle por edificación va tras JWT y no aparece en el visor público (T2, docs/07).
const WuiAgregado = z.object({
  ineCode: z.string(),
  total: z.number(),
  porClase: z.object({
    critico: z.number(),
    incumple: z.number(),
    cumple: z.number(),
  }),
  advertenciaMargen: z.number(),
  franja50Pendiente: z.number(),
  nota: z.string(),
  versionAnalisis: z.string().nullable(),
  descargo: z.string(),
});

export type MunicipioResumen = z.infer<typeof MunicipioResumen>;
export type Detalle = z.infer<typeof Detalle>;
export type PuntoSerie = z.infer<typeof PuntoSerie>;
export type Metodologia = z.infer<typeof Metodologia>;
export type Lista = z.infer<typeof Lista>;
export type WuiAgregado = z.infer<typeof WuiAgregado>;

async function pedir<T>(ruta: string, esquema: z.ZodType<T>): Promise<T> {
  const r = await fetch(`${ORIGIN}${ruta}`, { headers: { Accept: "application/json" } });
  if (!r.ok) {
    // 503 = índice obsoleto/no calculado; el cliente lo distingue para avisar, no para inventar.
    throw new ApiError(r.status, ruta);
  }
  return esquema.parse(await r.json());
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    ruta: string,
  ) {
    super(`API ${status} en ${ruta}`);
  }
}

export const listaMunicipios = () => pedir("/api/v1/municipios", Lista);
export const detalleMunicipio = (ine: string) =>
  pedir(`/api/v1/municipios/${ine}`, Detalle);
export const metodologia = () => pedir("/api/v1/metodologia", Metodologia);
export const wuiAgregado = (ine: string) =>
  pedir(`/api/v1/wui/agregado/${ine}`, WuiAgregado);
