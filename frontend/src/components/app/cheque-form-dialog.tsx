import { useEffect, useMemo, useState } from "react"
import { AlertTriangle, Check } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import type { Cheque } from "@/data/types"
import { lkDate } from "@/lib/calendar"
import { CHEQUE_CATEGORIES, toCheque, validateCheque, type ChequeErrors, type ChequeInput } from "@/lib/cheques"
import { fmt, parseAmount } from "@/lib/format"
import { useAppState } from "./app-state"
import { Field } from "./field"
import { MoneyInput } from "./money-input"

type Form = Omit<ChequeInput, "amount"> & { amount: string }

const fromCheque = (c: Cheque): Form => ({
  category: c.category, payee: c.payee, amount: fmt.amt(c.amount), cheque_no: c.cheque_no, cheque_date: c.cheque_date,
  expected_clear_date: c.expected_clear_date, notes: c.notes,
})
const blank = (date: string): Form => ({
  category: "", payee: "", amount: "", cheque_no: "", cheque_date: date, expected_clear_date: "", notes: "",
})

/**
 * Add or edit a future cheque payment. The forecast deducts it on its expected clearing date only — the issue date just
 * records when it was written. Changing the clearing date moves the deduction; it is never deducted twice, and the
 * cheque is never marked paid automatically.
 */
export function ChequeFormDialog({ open, onOpenChange, cheque, defaultDate, onSaved }: {
  open: boolean; onOpenChange: (o: boolean) => void
  /** The cheque to edit; omit to add a new one. */
  cheque?: Cheque | null
  /** Default cheque date for a new cheque (e.g. the selected calendar date). */
  defaultDate: string
  onSaved?: (c: Cheque) => void
}) {
  const { cheques, actions, today } = useAppState()
  const [f, setF] = useState<Form>(blank(defaultDate))
  const [errors, setErrors] = useState<ChequeErrors>({})
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  useEffect(() => { if (open) { setF(cheque ? fromCheque(cheque) : blank(defaultDate)); setErrors({}); setFailure(null) } }, [open]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = <K extends keyof Form>(k: K, v: Form[K]) => {
    setF((s) => ({ ...s, [k]: v }))
    setErrors((e) => ({ ...e, [k]: undefined })) // clear a field's error once it changes
  }
  /** Native date inputs follow the browser's locale, so also show the date as DD/MM/YYYY. */
  const asLk = (iso: string) => (/^\d{4}-\d{2}-\d{2}$/.test(iso) ? lkDate(iso) : "")
  const payees = useMemo(() => [...new Set(cheques.map((c) => c.payee))], [cheques])
  const input: ChequeInput = { ...f, amount: parseAmount(f.amount) }
  const draft = Object.keys(validateCheque(input, cheques, today, cheque?.cheque_no)).length === 0 ? toCheque(input, cheque ?? undefined) : null

  const save = async () => {
    const e = validateCheque(input, cheques, today, cheque?.cheque_no)
    setErrors(e)
    if (Object.keys(e).length) return
    const saved = toCheque(input, cheque ?? undefined)
    setSaving(true)
    const err = cheque ? await actions.updateCheque(cheque, saved) : await actions.addCheque(saved)
    setSaving(false)
    if (err) return setFailure(err)
    onOpenChange(false)
    onSaved?.(saved)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92svh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{cheque ? `Edit cheque #${cheque.cheque_no}` : "Add cheque payment"}</DialogTitle>
          <DialogDescription>A future payment. The forecast deducts it on its expected clearing date only.</DialogDescription>
        </DialogHeader>
        {failure && <Alert variant="destructive"><AlertTriangle /><AlertDescription>{failure}</AlertDescription></Alert>}
        <div className="grid gap-3.5 sm:grid-cols-2">
          <Field label="Payee" htmlFor="chq-payee" error={errors.payee}>
            <Input id="chq-payee" list="chq-payees" placeholder="e.g. Kelani Valley Foods" value={f.payee} onChange={(e) => set("payee", e.target.value)} aria-invalid={!!errors.payee || undefined} />
            <datalist id="chq-payees">{payees.map((p) => <option key={p} value={p} />)}</datalist>
          </Field>
          <Field label="Cheque number" htmlFor="chq-no" error={errors.cheque_no}>
            <Input id="chq-no" className="font-mono" inputMode="numeric" maxLength={6} placeholder="000000" value={f.cheque_no} onChange={(e) => set("cheque_no", e.target.value.replace(/\D/g, ""))} aria-invalid={!!errors.cheque_no || undefined} />
          </Field>
          <Field label="Amount (LKR)" htmlFor="chq-amt" error={errors.amount}>
            <MoneyInput id="chq-amt" value={f.amount} onChange={(v) => set("amount", v)} invalid={!!errors.amount} />
          </Field>
          <Field label="Payment category" error={errors.category}>
            <Select value={f.category} onValueChange={(v) => set("category", v)}>
              <SelectTrigger className="w-full" aria-label="Payment category" aria-invalid={!!errors.category || undefined}><SelectValue placeholder="Choose category" /></SelectTrigger>
              <SelectContent>{CHEQUE_CATEGORIES.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}</SelectContent>
            </Select>
          </Field>
          <Field label="Cheque issue date" htmlFor="chq-date" error={errors.cheque_date} help={f.cheque_date ? `${asLk(f.cheque_date)} · when the cheque is written` : "When the cheque is written"}>
            <Input id="chq-date" type="date" value={f.cheque_date} onChange={(e) => set("cheque_date", e.target.value)} aria-invalid={!!errors.cheque_date || undefined} />
          </Field>
          <Field label="Expected clearing date" htmlFor="chq-clear" error={errors.expected_clear_date} help={f.expected_clear_date ? `${asLk(f.expected_clear_date)} · the day the money is expected to leave the current account` : "The day the money is expected to leave the current account"}>
            <Input id="chq-clear" type="date" min={f.cheque_date || undefined} value={f.expected_clear_date} onChange={(e) => set("expected_clear_date", e.target.value)} aria-invalid={!!errors.expected_clear_date || undefined} />
          </Field>
          <div className="bg-muted rounded-md border px-3.5 py-2.5 text-[13px] sm:col-span-2" aria-live="polite">
            {draft ? (
              <>
                Forecast deducts <b className="tabular">{fmt.lkr(draft.amount)}</b> on <b>{lkDate(draft.expected_clear_date)}</b>, the expected clearing date.
                {draft.expected_clear_date !== draft.cheque_date && <span className="text-muted-foreground"> Nothing is deducted on the issue date ({lkDate(draft.cheque_date)}).</span>}
                <span className="text-muted-foreground"> It does not change the Actual Bank Balance.</span>
              </>
            ) : <span className="text-muted-foreground">Complete the form to see when the forecast deducts this payment.</span>}
          </div>
          <Field label="Notes (optional)" htmlFor="chq-notes" className="sm:col-span-2">
            <Textarea id="chq-notes" rows={2} placeholder="Anything the team should know" value={f.notes} onChange={(e) => set("notes", e.target.value)} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={save} disabled={saving}><Check />{saving ? "Saving…" : cheque ? "Save changes" : "Add cheque"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Cancel a scheduled cheque, with the reason the payment service records. A cancelled cheque no longer counts as a future outflow. */
export function CancelChequeDialog({ cheque, onClose, onCancelled }: { cheque: Cheque | null; onClose: () => void; onCancelled?: (c: Cheque) => void }) {
  const { actions } = useAppState()
  const [reason, setReason] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  useEffect(() => { setReason(""); setError(null) }, [cheque])
  const cancel = async (c: Cheque) => {
    if (!reason.trim()) return setError("Enter the reason for cancelling.")
    setSaving(true)
    const err = await actions.cancelCheque(c, reason.trim())
    setSaving(false)
    if (err) return setError(err)
    onClose()
    onCancelled?.(c)
  }
  return (
    <Dialog open={!!cheque} onOpenChange={(o) => !o && onClose()}>
      {cheque && (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Cancel cheque #{cheque.cheque_no}?</DialogTitle>
            <DialogDescription>
              {cheque.payee} · {fmt.lkr(cheque.amount)}. It will stop counting as a future outflow, and the forecast will be recalculated.
            </DialogDescription>
          </DialogHeader>
          <Field label="Reason" htmlFor="cancel-reason" error={error ?? undefined}>
            <Textarea id="cancel-reason" rows={2} placeholder="e.g. Paid by bank transfer instead" value={reason} onChange={(e) => { setReason(e.target.value); setError(null) }} aria-invalid={!!error || undefined} />
          </Field>
          <DialogFooter>
            <Button variant="outline" onClick={onClose}>Keep cheque</Button>
            <Button variant="destructive" disabled={saving} onClick={() => void cancel(cheque)}>{saving ? "Cancelling…" : "Cancel cheque"}</Button>
          </DialogFooter>
        </DialogContent>
      )}
    </Dialog>
  )
}
