import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Puerto 5173 para casar con la URL documentada del visor (README).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    // En desarrollo, el visor pide /api al mismo origen; se reenvía a la api local.
    // En producción es Apache/nginx quien hace de reverse proxy (ver web/nginx.conf).
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
