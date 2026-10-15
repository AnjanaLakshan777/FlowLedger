import type { ReactNode } from "react"
import { AlertTriangle, Loader2, RefreshCw } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { useAppState, type Resource } from "./app-state"

/**
 * Shows the loading or error state of the data a screen needs, or nothing once it is ready.
 * A failed request is reported as such — screens never fall back to other data.
 */
export function DataState({ resources, what }: { resources: Resource[]; what: string }) {
  const { resources: state, reload } = useAppState()
  const failed = resources.filter((r) => state[r].error)
  if (failed.length) {
    return (
      <Alert variant="destructive">
        <AlertTriangle />
        <AlertTitle>{what} could not be loaded</AlertTitle>
        <AlertDescription>
          <p>{[...new Set(failed.map((r) => state[r].error))].join(" ")}</p>
          <Button variant="outline" size="sm" className="mt-1" onClick={() => failed.forEach((r) => reload(r))}><RefreshCw />Try again</Button>
        </AlertDescription>
      </Alert>
    )
  }
  if (resources.some((r) => state[r].loading)) return <LoadingCard>Loading {what.toLowerCase()}…</LoadingCard>
  return null
}

export function LoadingCard({ children }: { children: ReactNode }) {
  return (
    <Card className="text-muted-foreground flex-row items-center justify-center gap-2 px-4 py-10 text-sm" role="status">
      <Loader2 className="size-4 animate-spin" />{children}
    </Card>
  )
}

/** True when the screen's data is still loading or failed, so the screen shows `DataState` instead. */
export function useNotReady(resources: Resource[]) {
  const { resources: state } = useAppState()
  return resources.some((r) => state[r].loading || state[r].error)
}
