/// <reference types="vitest/config" />
import path from "node:path"
import { defineConfig, loadEnv } from "vite"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"

export default defineConfig(({ mode }) => {
  // Only API_GATEWAY_* is read from the repository-root .env; its secrets never reach the frontend.
  const env = loadEnv(mode, path.resolve(__dirname, ".."), "API_GATEWAY_")
  // In development every /api request goes to the API gateway, so the browser talks to one origin.
  const proxy = { "/api": { target: env.API_GATEWAY_URL || "http://localhost:8088", changeOrigin: true } }

  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
    server: { proxy },
    preview: { proxy },
    build: { chunkSizeWarningLimit: 900 },
    test: { include: ["src/**/*.test.{ts,tsx}"], environment: "node" },
  }
})
