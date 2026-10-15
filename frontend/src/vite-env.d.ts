/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" shows the fictional demonstration data instead of the FlowLedger backend. */
  readonly VITE_DEMO_MODE?: string
  /** API gateway origin when the frontend is not served from the same origin, e.g. https://api.example.com. Never put secrets in VITE_ variables. */
  readonly VITE_API_BASE_URL?: string
}
