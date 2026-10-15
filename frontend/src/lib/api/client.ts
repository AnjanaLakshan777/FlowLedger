/**
 * The one place the frontend talks to the FlowLedger backend, always through the API gateway:
 * same-origin /api/v1/... paths (the Vite proxy forwards them in development), or the gateway URL
 * in VITE_API_BASE_URL when the frontend is served from another origin. The access token is the
 * only credential the browser holds; service-to-service secrets never reach the frontend.
 */
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "")

/** An error answer from a service, in its RFC 9457 problem-details form. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** Field errors from a 400 validation answer, keyed by field name. */
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message)
  }
}

const TOKEN_KEY = "flowledger.accessToken"
let token: string | null = readStoredToken()
let onUnauthorized: (() => void) | null = null

function readStoredToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY) ?? localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export const session = {
  token: () => token,
  /** `remember` keeps the token after the browser closes; it still expires on the server's schedule. */
  set(next: string, remember: boolean) {
    token = next
    try {
      ;(remember ? localStorage : sessionStorage).setItem(TOKEN_KEY, next)
      ;(remember ? sessionStorage : localStorage).removeItem(TOKEN_KEY)
    } catch {
      // storage blocked: the token lives in memory for this tab only
    }
  },
  clear() {
    token = null
    try {
      sessionStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(TOKEN_KEY)
    } catch {
      // nothing stored
    }
  },
  /** Called when a request is refused for an invalid or expired token. */
  onUnauthorized(handler: (() => void) | null) {
    onUnauthorized = handler
  },
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH"
  json?: unknown
  form?: FormData
  signal?: AbortSignal
  /** Login answers 401 for wrong credentials; that is not an expired session. */
  anonymous?: boolean
}

export async function api<T>(path: string, { method = "GET", json, form, signal, anonymous }: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" }
  if (token && !anonymous) headers.Authorization = `Bearer ${token}`
  if (json !== undefined) headers["Content-Type"] = "application/json"

  let res: Response
  try {
    res = await fetch(BASE_URL + path, { method, headers, body: json !== undefined ? JSON.stringify(json) : form, signal })
  } catch (e) {
    if ((e as Error).name === "AbortError") throw e
    throw new ApiError(0, "FlowLedger could not be reached. Check your connection and try again.")
  }

  const text = await res.text()
  const body = text ? safeJson(text) : null
  if (res.ok) return body as T

  if (res.status === 401 && !anonymous) {
    onUnauthorized?.()
    throw new ApiError(401, "Your session has ended. Please sign in again.")
  }
  const problem = (body ?? {}) as { detail?: string; title?: string; errors?: Record<string, string> }
  throw new ApiError(res.status, problem.detail ?? problem.title ?? `Request failed (${res.status}).`, problem.errors ?? {})
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/** A readable message for any error from `api`. */
export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong.")
