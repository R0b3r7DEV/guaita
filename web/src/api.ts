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
  pesos: z.object({
    estructural: z.number(),
    vulnerab: z.number(),
    poblacion: z.number(),
    espacioProtegido: z.number(),
  }),
  normaPoblacion: z.string(),
  meteoVentanaDias: z.number(),
  niveles: z.array(z.number()),
  etiquetasNivel: z.array(z.string()),
  caveats: z.array(z.string()),
  documentacion: z.string(),
  aviso: z.string(),
});

export type MunicipioResumen = z.infer<typeof MunicipioResumen>;
export type Detalle = z.infer<typeof Detalle>;
export type PuntoSerie = z.infer<typeof PuntoSerie>;
export type Metodologia = z.infer<typeof Metodologia>;
export type Lista = z.infer<typeof Lista>;

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
