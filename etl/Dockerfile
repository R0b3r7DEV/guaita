# Contenedor de ETL de geodatos estáticos (Fase 1). NO forma parte del runtime:
# se ejecuta a demanda con `make seed` (servicio bajo profile en compose).
# Trae GDAL (ogr2ogr, gdaldem, gdal_*) + Python GDAL. Le añadimos el cliente
# psql (para las aserciones de integridad) y curl (descargas).
FROM osgeo/gdal:ubuntu-small-latest

# NOTA: 'latest' se debe pinnear a una versión concreta (p. ej.
# ubuntu-small-3.10.x) tras el primer pull verificado, coherente con el resto
# del repo. Se deja pendiente hasta confirmar el tag disponible.
RUN apt-get update \
  && apt-get install -y --no-install-recommends postgresql-client curl ca-certificates unzip \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /etl
COPY . /etl
RUN chmod +x /etl/*.sh
ENTRYPOINT ["bash"]
