// Cinco clases de peligro (docs/04 §4), rampa verde→granate VALIDADA para daltonismo con el
// validador de dataviz: separación CVD deutan/protan/tritan >= 12.4 y normal >= 15.7 en todos los
// pares adyacentes (umbral 8/15). El granate queda con contraste bajo sobre el fondo oscuro (WARN),
// compensado con relieve: leyenda con rangos + bordes de término + panel al clic.
export interface Nivel {
  nivel: number;
  etiqueta: string;
  rango: string;
  color: string;
}

export const NIVELES: readonly Nivel[] = [
  { nivel: 1, etiqueta: "Bajo", rango: "0–20", color: "#4caf50" },
  { nivel: 2, etiqueta: "Moderado", rango: "21–40", color: "#f6c722" },
  { nivel: 3, etiqueta: "Alto", rango: "41–60", color: "#f0861b" },
  { nivel: 4, etiqueta: "Muy alto", rango: "61–80", color: "#e02b1f" },
  { nivel: 5, etiqueta: "Extremo", rango: "81–100", color: "#8e1a42" },
];

// Municipio sin índice calculado: gris neutro, distinto de cualquier clase de peligro.
export const SIN_DATO = "#3a3f3c";

export function colorNivel(nivel: number): string {
  return NIVELES.find((n) => n.nivel === nivel)?.color ?? SIN_DATO;
}

export function etiquetaNivel(nivel: number): string {
  return NIVELES.find((n) => n.nivel === nivel)?.etiqueta ?? "Sin dato";
}
