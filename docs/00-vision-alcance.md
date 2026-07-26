# 00 · Visión y alcance

## El problema

Castellón ha sufrido cuatro eventos graves en cuatro años: Bejís (agosto 2022,
~19.000 ha, once términos), Costur–Les Useres–Figueroles–Llucena (agosto 2022,
800 ha), Villanueva de Viver (marzo 2023, 4.700 ha) y la Serra d'Espadà (julio
2026, en curso).

Mientras tanto, los planes de prevención de incendios forestales de demarcación
(PPIFDF) — que según el art. 47 del Decreto 91/2023 constituyen el desarrollo
táctico del PATFOR y equivalen a los planes de defensa de las zonas de alto
riesgo — **fueron redactados entre 2007 y 2013 y actualizados entre 2013 y 2014**.
La cartografía táctica sobre la que se planifica describe un territorio que ya no
existe: han ardido decenas de miles de hectáreas, el interior se ha vaciado más y
las cicatrices de 2022–2023 están regenerando como matorral joven, que arde peor
que el pinar adulto que sustituyó.

Existe cartografía buena (Mapa de Peligro de la CV, 2024, con LIDAR y simulación
de campos de viento) pero es un producto estático, no consultable por un
ayuntamiento pequeño, y no se actualiza con lo que pasó el mes pasado.

## Qué hace GUAITA

Tres cosas, en orden de valor:

1. **Índice de peligro diario por municipio.** Combina un componente
   meteorológico calculado (FWI canadiense completo, no una heurística) con un
   componente estructural (combustible, pendiente, tiempo desde el último
   incendio) y uno de vulnerabilidad (población e inmuebles en interfaz).
   Actualización diaria automática.

2. **Auditoría de interfaz urbano-forestal.** Cruza edificaciones del Catastro
   con la capa de terreno forestal del PATFOR y devuelve, por término municipal,
   el listado de inmuebles cuya franja perimetral de 25–30 m contiene combustible
   forestal — es decir, en situación de incumplimiento del anexo XI del TRLOTUP.
   Esto es accionable el mismo día por un ayuntamiento.

3. **Validación histórica.** Backtest del índice contra las fechas reales de los
   grandes incendios de la provincia. Sin esto el proyecto es un dashboard
   bonito; con esto es un trabajo defendible.

## Quién lo usa

- **Técnico municipal de un ayuntamiento pequeño del interior.** No tiene SIG ni
  presupuesto. Quiere saber qué días extremar la vigilancia y qué casas de su
  término están en riesgo. Es el usuario primario.
- **Ciudadano en diseminado o urbanización.** Quiere saber si su parcela cumple y
  recibir aviso los días malos.
- **Perfil técnico / evaluador.** Quiere ver la metodología y el backtest. Es a
  quien va dirigida la parte de validación.

## Fuera de alcance (explícito)

- No es un sistema de emergencia ni de despacho de medios. **No sustituye al 112
  ni al boletín PREVIFOC oficial.** Aviso permanente en la UI.
- No simula propagación del fuego. Eso es FARSITE/Wildfire Analyst y requiere
  datos y validación fuera del alcance de un proyecto individual.
- No hace detección de humo por visión artificial.
- No cubre otras provincias en v1. La arquitectura no lo impide, pero el alcance
  geográfico se cierra en Castellón para poder terminar.
- No publica datos personales. El módulo de interfaz trabaja con referencias
  catastrales y geometrías de edificio, nunca con titulares.

## Criterio de éxito

El proyecto está terminado cuando:

- El índice se calcula solo, cada día, para los 135 municipios de la provincia.
- El backtest muestra un AUC ≥ 0,75 discriminando días de gran incendio.
- Un ayuntamiento puede descargar el informe de interfaz de su término en PDF.
- Está desplegado, con HTTPS, en el VPS y lleva 30 días sin intervención manual.

## Nota de responsabilidad

El autor no es ingeniero forestal colegiado. GUAITA implementa índices publicados
y revisados por pares (FWI de Van Wagner 1987) y datos oficiales, pero sus
salidas son orientativas. Todo informe generado debe llevar el descargo de
responsabilidad definido en `docs/07-seguridad.md`.
