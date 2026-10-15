/// <reference types="vitest/config" />
import path from "node:path"
import { defineConfig } from "vite"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"

// Each backend service owns one API prefix. In development the Vite server forwards them, so the
// browser talks to one origin and the services need no CORS setup. A deployment needs a gateway
// with the same routes.
const service = (port: number) => ({ target: `http://localhost:${port}`, changeOrigin: true })
const proxy = {
  "/api/v1/identity": service(8080),
  "/api/v1/cash-management": service(8081),
  "/api/v1/bank-integration": service(8082),
  "/api/v1/payment": service(8083),
  "/api/v1/forecast": service(8084),
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
  server: { proxy },
  preview: { proxy },
  build: { chunkSizeWarningLimit: 900 },
  test: { include: ["src/**/*.test.{ts,tsx}"], environment: "node" },
})
