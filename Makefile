# GUAITA — atajos de desarrollo. Requiere Docker Compose v2.
# Las migraciones Flyway se aplican solas al arrancar la api (Spring Boot).

.PHONY: up down logs migrate test lint seed ingest backtest

up:            ## Levanta db, api y web; bloquea hasta que estén healthy
	docker compose up -d --build --wait

down:          ## Para y elimina los contenedores
	docker compose down

logs:          ## Sigue los logs de todos los servicios
	docker compose logs -f

migrate:       ## Aplica migraciones (reinicia api; Flyway corre en el arranque)
	docker compose up -d --build api

test:          ## Tests del backend (Testcontainers levanta PostGIS real)
	cd api && ./gradlew test

lint:          ## Formato/estilo: Spotless (api) + typecheck (web)
	cd api && ./gradlew spotlessCheck
	cd web && npm run lint

# --- Aún no implementados; pertenecen a fases posteriores del roadmap ---
seed:          ## [Fase 1] Carga de geodatos estáticos
	@echo "seed: geodatos estáticos — Fase 1, aún no implementado." && exit 1

ingest:        ## [Fase 2] Primera pasada de feeds vivos
	@echo "ingest: feeds vivos — Fase 2, aún no implementado." && exit 1

backtest:      ## [Fase 4] Validación histórica del índice
	@echo "backtest: validación histórica — Fase 4, aún no implementado." && exit 1
