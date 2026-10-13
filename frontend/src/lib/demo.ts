import { toast } from "sonner"

/**
 * For actions that would leave the browser (bank sync, emails, file processing).
 * They need backend APIs that don't exist yet, so say so instead of pretending they happened.
 */
export function requiresBackend(action: string, detail = "Nothing was sent.") {
  toast.info(`${action} needs the FlowLedger backend`, { description: `Not available in this demonstration. ${detail}` })
}
