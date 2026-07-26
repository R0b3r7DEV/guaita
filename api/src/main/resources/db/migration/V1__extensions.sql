-- V1 · Extensiones de PostgreSQL necesarias para GUAITA.
-- Se aplican por Flyway al arrancar la api (y en los tests Testcontainers).
--   postgis   : tipos y funciones geoespaciales (SRID 25830, ST_*).
--   citext    : texto case-insensitive (p. ej. suscripcion.email, doc 03).
--   pgcrypto  : gen_random_uuid() para claves primarias uuid (doc 03).

create extension if not exists postgis;
create extension if not exists citext;
create extension if not exists pgcrypto;
