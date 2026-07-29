# Contenedor de ETL de geodatos estáticos (Fase 1). NO forma parte del runtime:
# se ejecuta a demanda con `make seed` (servicio bajo profile en compose).
# Trae GDAL (ogr2ogr, gdaldem, gdal_*) + Python GDAL. Le añadimos el cliente
# psql (para las aserciones de integridad) y curl (descargas).
FROM ghcr.io/osgeo/gdal:ubuntu-small-latest

# Imagen viva en ghcr.io (el repo Docker Hub osgeo/gdal está obsoleto: se quedó
# en 3.6.3, 2023). El tag ubuntu-small-latest está verificado como existente.
# Pinnear a una versión/digest concreta queda pendiente, coherente con el
# pinning del resto del repo.
RUN apt-get update \
  && apt-get install -y --no-install-recommends postgresql-client curl ca-certificates unzip python3-numpy \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /etl
COPY . /etl
RUN chmod +x /etl/*.sh
ENTRYPOINT ["bash"]
