import { PlugZap } from "lucide-react"
import { Card } from "@/components/ui/card"
import { PageHeader } from "./page-header"

/** A screen whose backend API does not exist yet. It shows no demonstration data in place of company data. */
export function NotConnected({ title, needs }: { title: string; needs: string[] }) {
  return (
    <>
      <PageHeader title={title} />
      <Card className="items-center gap-3 px-4 py-12 text-center text-sm">
        <PlugZap className="text-muted-foreground size-7" />
        <h2 className="text-[15px] font-semibold">Not connected to FlowLedger data yet</h2>
        <p className="text-muted-foreground max-w-[60ch]">This screen needs backend APIs that do not exist yet:</p>
        <ul className="text-muted-foreground grid gap-1 text-[13px]">{needs.map((n) => <li key={n}>{n}</li>)}</ul>
      </Card>
    </>
  )
}
