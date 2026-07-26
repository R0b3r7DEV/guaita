import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Puerto 5173 para casar con la URL documentada del visor (README).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
  },
});
