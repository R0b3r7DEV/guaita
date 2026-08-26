-- V18 · Autenticación para el detalle IUF (T2 de docs/07). SIN auto-registro: las cuentas las crea
-- un administrador (135 municipios = conjunto cerrado y pequeño). Un técnico solo ve el detalle de
-- SU término. El detalle por edificación es sensible; el agregado por municipio es público.
create extension if not exists citext;

create table usuario (
  id            uuid primary key default gen_random_uuid(),
  email         citext unique not null,
  password_hash text not null,                         -- Argon2id (nunca la contraseña en claro)
  ine_code      char(5) references municipio(ine_code),-- término autorizado; NULL para rol admin
  rol           text not null default 'tecnico' check (rol in ('tecnico', 'admin')),
  creado_en     timestamptz not null default now(),
  constraint usuario_ine_por_rol
    check ((rol = 'admin') or (rol = 'tecnico' and ine_code is not null))
);

-- Refresh tokens con ROTACIÓN y DETECCIÓN DE REUTILIZACIÓN (docs/07): se guarda el HASH del token,
-- no el token. Cada refresh emite uno nuevo en la misma `familia` y marca el anterior `usado`. Si
-- llega un token ya `usado` -> robo -> se revoca la familia entera y se fuerza reautenticación.
create table refresh_token (
  id         uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references usuario(id) on delete cascade,
  token_hash text not null unique,                     -- SHA-256 del token opaco
  familia    uuid not null,
  emitido_en timestamptz not null default now(),
  expira_en  timestamptz not null,
  usado      boolean not null default false,
  revocado   boolean not null default false
);
create index rt_usuario_ix on refresh_token (usuario_id);
create index rt_familia_ix on refresh_token (familia);
