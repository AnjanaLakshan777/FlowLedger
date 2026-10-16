import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react"
import { useSearchParams } from "react-router-dom"
import { Check, Lock, MoreHorizontal, Pencil, Plus, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useAppState } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { LoadingCard } from "@/components/app/data-state"
import { Field } from "@/components/app/field"
import { PageHeader } from "@/components/app/page-header"
import { SearchInput } from "@/components/app/search-input"
import { StatusBadge } from "@/components/app/status-badge"
import type { Role } from "@/data/types"
import { colomboLocal } from "@/lib/api/adapters"
import { ApiError, errorMessage } from "@/lib/api/client"
import {
  flowledger, type ApiAccessChange, type ApiBankAccount, type ApiCompany, type ApiMembership, type CompanyRole, type IntegrationMode,
} from "@/lib/api/flowledger"
import { fmt, initials } from "@/lib/format"
import { can, PERMISSION_LABEL } from "@/lib/permissions"

const COMPANY_ROLES: CompanyRole[] = ["CASHIER", "ACCOUNTANT", "FINANCE_CONTROLLER", "COMPANY_ADMIN"]
const ROLE_NAME: Record<CompanyRole, string> = {
  COMPANY_ADMIN: "Company Admin", FINANCE_CONTROLLER: "Finance Controller", ACCOUNTANT: "Accountant", CASHIER: "Cashier",
}
const ROLE_HELP: Record<CompanyRole, string> = {
  CASHIER: "Enters daily cash and card sales. No other pages.",
  ACCOUNTANT: "Enters daily sales, imports historical sales and views the finance pages.",
  FINANCE_CONTROLLER: "All finance operations: sales, imports and approvals, cheque payments and the forecast.",
  COMPANY_ADMIN: "Everything, plus users, roles, company settings and bank configuration.",
}
/** The UI permission set of each company role (Company Admin uses the administrator set). */
const UI_ROLE: Record<CompanyRole, Role> = {
  COMPANY_ADMIN: "SYSTEM_ADMIN", FINANCE_CONTROLLER: "FINANCE_CONTROLLER", ACCOUNTANT: "ACCOUNTANT", CASHIER: "CASHIER",
}
const STATUS_LABEL: Record<string, string> = { ACTIVE: "Active", INVITED: "Invited", SUSPENDED: "Deactivated" }
const ACCOUNT_LABEL: Record<string, string> = { PENDING: "Account pending", LOCKED: "Account locked", DISABLED: "Account disabled" }
const ACTION_LABEL: Record<string, string> = {
  MEMBERSHIP_CREATED: "Added user", MEMBERSHIP_ROLE_CHANGED: "Changed role", MEMBERSHIP_STATUS_CHANGED: "Changed status", COMPANY_UPDATED: "Edited company",
}
const FIELD_LABEL: Record<string, string> = { name: "Company name", registrationNumber: "Registration no." }
const MODE_LABEL: Record<string, string> = { STATEMENT_IMPORT: "Bank statement import", MANUAL: "Manual entry", BANK_API: "Bank API", SIMULATOR: "Simulator" }

/** Role and status codes in the access-change history, as people read them. */
const changeValue = (value: string | null) => (value ? ROLE_NAME[value as CompanyRole] ?? STATUS_LABEL[value] ?? value : null)
const when = (instant: string) => fmt.dateTime(colomboLocal(instant))

interface Data<T> { value: T | null; error: string | null }
const EMPTY = { value: null, error: null }

/**
 * Users & Settings with company data from identity-service and bank-integration-service. Every
 * permission is checked by the services; this screen only hides what the role cannot do.
 */
export default function LiveUsersSettingsPage() {
  const { company, currentUser, allow, reload } = useAppState()
  const cid = company?.id ?? null
  const me = currentUser?.user_id ?? null
  const [params] = useSearchParams()
  const [tab, setTab] = useState(params.get("tab") ?? "users")
  const [q, setQ] = useState("")
  const [members, setMembers] = useState<Data<ApiMembership[]>>(EMPTY)
  const [details, setDetails] = useState<Data<ApiCompany>>(EMPTY)
  const [accounts, setAccounts] = useState<Data<ApiBankAccount[]>>(EMPTY)
  const [changes, setChanges] = useState<Data<ApiAccessChange[]>>(EMPTY)
  const [createOpen, setCreateOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [bankOpen, setBankOpen] = useState(false)
  const isAdmin = allow("users.manage")

  const load = useCallback(<T,>(fetch: () => Promise<T>, set: (d: Data<T>) => void) => {
    fetch().then((value) => set({ value, error: null })).catch((e) => set({ value: null, error: errorMessage(e) }))
  }, [])
  const loadMembers = useCallback(() => { if (cid) load(() => flowledger.memberships(cid), setMembers) }, [cid, load])
  const loadCompany = useCallback(() => { if (cid) load(() => flowledger.company(cid), setDetails) }, [cid, load])
  const loadAccounts = useCallback(() => { if (cid) load(() => flowledger.bankAccounts(cid), setAccounts) }, [cid, load])
  const loadChanges = useCallback(() => { if (cid) load(() => flowledger.accessChanges(cid), setChanges) }, [cid, load])

  useEffect(() => {
    setMembers(EMPTY); setDetails(EMPTY); setAccounts(EMPTY); setChanges(EMPTY)
    loadMembers(); loadCompany(); loadAccounts(); loadChanges()
  }, [loadMembers, loadCompany, loadAccounts, loadChanges])

  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return (members.value ?? []).filter((m) => !s || `${m.fullName} ${m.email} ${ROLE_NAME[m.companyRole]}`.toLowerCase().includes(s))
  }, [members.value, q])
  const activeAdmins = (members.value ?? []).filter((m) => m.companyRole === "COMPANY_ADMIN" && m.status === "ACTIVE").length

  async function change(m: ApiMembership, body: { companyRole?: CompanyRole; status?: "ACTIVE" | "SUSPENDED" }, done: string) {
    if (!cid) return
    try {
      await flowledger.updateMembership(cid, m.membershipId, body)
      toast.success(done, { description: "Recorded in Access changes" })
    } catch (e) {
      toast.error("The change was not saved", { description: errorMessage(e) })
    }
    loadMembers(); loadChanges()
  }

  const readOnly = !isAdmin && (
    <Alert><Lock /><AlertTitle>View only</AlertTitle><AlertDescription>Only a Company Admin can manage users, roles and settings.</AlertDescription></Alert>
  )
  const failed = (error: string | null, retry: () => void) => error && (
    <Alert variant="destructive">
      <AlertTitle>Could not load this section</AlertTitle>
      <AlertDescription className="grid justify-items-start gap-2">{error}<Button size="sm" variant="outline" onClick={retry}><RefreshCw />Try again</Button></AlertDescription>
    </Alert>
  )
  const account = (accounts.value ?? []).find((a) => a.accountType === "CURRENT" && a.status === "ACTIVE") ?? null

  return (
    <>
      <PageHeader
        title="Users & Settings"
        description="Who can use FlowLedger, what each role can do, and company-wide settings. Users only ever see their own company's financial records."
        actions={tab === "users" && isAdmin && <Button onClick={() => setCreateOpen(true)}><Plus />Create user</Button>}
      />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="max-w-full flex-wrap">
          <TabsTrigger value="users">Users</TabsTrigger>
          <TabsTrigger value="roles">Roles & permissions</TabsTrigger>
          <TabsTrigger value="company">Company</TabsTrigger>
          <TabsTrigger value="bank">Bank account</TabsTrigger>
          {isAdmin && <TabsTrigger value="access">Access changes</TabsTrigger>}
        </TabsList>

        <TabsContent value="users" className="grid gap-4">
          {readOnly}
          {failed(members.error, loadMembers)}
          {!members.value && !members.error && <LoadingCard>Loading users…</LoadingCard>}
          {members.value && (
            <Card>
              <CardContent><SearchInput value={q} onChange={setQ} placeholder="Search name, email or role" /></CardContent>
              <DataTable<ApiMembership>
                rows={rows}
                rowKey={(r) => r.membershipId}
                defaultSort={{ key: "name", dir: "asc" }}
                maxHeight="none"
                empty="No users match the search."
                columns={[
                  {
                    key: "name", header: "Name", sortValue: (r) => r.fullName, cell: (r) => (
                      <div className="flex items-center gap-2.5">
                        <span className="bg-primary/10 text-primary dark:text-chart-2 grid size-7 shrink-0 place-items-center rounded-full text-[11px] font-semibold">{initials(r.fullName)}</span>
                        <CellStack main={<b className="font-semibold">{r.fullName}{r.userId === me && <span className="text-muted-foreground font-normal"> (you)</span>}</b>} sub={r.email} />
                      </div>
                    ),
                  },
                  { key: "role", header: "Role", sortValue: (r) => r.companyRole, cell: (r) => <Badge variant={r.companyRole === "COMPANY_ADMIN" ? "secondary" : "outline"}>{ROLE_NAME[r.companyRole]}</Badge> },
                  {
                    key: "status", header: "Status", sortValue: (r) => r.status, cell: (r) => (
                      <div className="flex flex-wrap gap-1">
                        <StatusBadge status={STATUS_LABEL[r.status]} />
                        {ACCOUNT_LABEL[r.accountStatus] && <Badge variant="attention">{ACCOUNT_LABEL[r.accountStatus]}</Badge>}
                      </div>
                    ),
                  },
                  { key: "created", header: "Added", sortValue: (r) => r.createdAt, cell: (r) => <span className="text-muted-foreground whitespace-nowrap">{fmt.date(colomboLocal(r.createdAt))}</span> },
                  {
                    key: "act", header: "", cell: (r) => {
                      if (!isAdmin) return null
                      // nobody changes their own membership — another admin must
                      if (r.userId === me) return <span className="text-muted-foreground text-xs">Your account</span>
                      const lastAdmin = r.companyRole === "COMPANY_ADMIN" && r.status === "ACTIVE" && activeAdmins <= 1
                        ? "This is the company's last active Company Admin." : null
                      return (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" aria-label={`Actions for ${r.fullName}`}><MoreHorizontal /></Button></DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-64">
                            <DropdownMenuLabel>Role</DropdownMenuLabel>
                            <DropdownMenuRadioGroup value={r.companyRole} onValueChange={(v) => v !== r.companyRole && change(r, { companyRole: v as CompanyRole }, `${r.fullName} is now ${ROLE_NAME[v as CompanyRole]}`)}>
                              {COMPANY_ROLES.map((x) => (
                                <DropdownMenuRadioItem key={x} value={x} disabled={x !== r.companyRole && !!lastAdmin}>{ROLE_NAME[x]}</DropdownMenuRadioItem>
                              ))}
                            </DropdownMenuRadioGroup>
                            {lastAdmin && <DropdownMenuLabel className="text-muted-foreground text-xs font-normal">{lastAdmin}</DropdownMenuLabel>}
                            <DropdownMenuSeparator />
                            {r.status === "ACTIVE" ? (
                              <DropdownMenuItem variant="destructive" disabled={!!lastAdmin} onSelect={() => change(r, { status: "SUSPENDED" }, `${r.fullName} deactivated`)}>Deactivate</DropdownMenuItem>
                            ) : (
                              <DropdownMenuItem onSelect={() => change(r, { status: "ACTIVE" }, `${r.fullName} ${r.status === "INVITED" ? "activated" : "reactivated"}`)}>
                                {r.status === "INVITED" ? "Activate" : "Reactivate"}
                              </DropdownMenuItem>
                            )}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )
                    },
                  },
                ]}
              />
            </Card>
          )}
        </TabsContent>

        <TabsContent value="roles">
          <Card>
            <CardHeader><CardTitle>What each role can do</CardTitle><CardDescription>Roles are assigned per user by a Company Admin. Permissions come from the role.</CardDescription></CardHeader>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow><TableHead>Permission</TableHead>{COMPANY_ROLES.map((r) => <TableHead key={r} className="text-center">{ROLE_NAME[r]}</TableHead>)}</TableRow>
                </TableHeader>
                <TableBody>
                  {PERMISSION_LABEL.map(([perm, label]) => (
                    <TableRow key={perm}>
                      <TableCell>{perm.startsWith("page.") ? <>Open <b className="font-medium">{label}</b></> : label}</TableCell>
                      {COMPANY_ROLES.map((r) => (
                        <TableCell key={r} className="text-center">
                          {can(UI_ROLE[r], perm) ? <Check className="text-primary dark:text-chart-2 mx-auto size-4" aria-label="Allowed" /> : <span className="text-muted-foreground" aria-label="Not allowed">—</span>}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <CardContent className="text-muted-foreground grid gap-1 text-xs">
              {COMPANY_ROLES.map((r) => <p key={r}><b className="text-foreground font-medium">{ROLE_NAME[r]}:</b> {ROLE_HELP[r]}</p>)}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="company" className="grid gap-4">
          {readOnly}
          {failed(details.error, loadCompany)}
          {!details.value && !details.error && <LoadingCard>Loading company details…</LoadingCard>}
          {details.value && (
            <div className="grid gap-4 xl:grid-cols-2">
              <Card>
                <CardHeader>
                  <CardTitle>Company</CardTitle>
                  <CardAction><Button variant="outline" size="sm" disabled={!isAdmin} onClick={() => setEditOpen(true)}><Pencil />Edit</Button></CardAction>
                </CardHeader>
                <CardContent>
                  <Details items={[
                    ["Company name", details.value.name],
                    ["Business registration no.", details.value.registrationNumber ?? "—"],
                    ["Reporting currency", details.value.defaultCurrency === "LKR" ? "LKR - Sri Lankan Rupee" : details.value.defaultCurrency],
                    ["Time zone", details.value.timezone === "Asia/Colombo" ? "Asia/Colombo (UTC+05:30)" : details.value.timezone],
                  ]} />
                </CardContent>
              </Card>
              <Card>
                <CardHeader><CardTitle>Forecast</CardTitle><CardAction><span className="text-muted-foreground text-xs">Fixed for the MVP</span></CardAction></CardHeader>
                <CardContent>
                  <Details items={[
                    ["Forecast horizon", "12 weeks (84 days)"],
                    ["Predicted sales", "Cash and card sales from last year's comparable weeks and weekday pattern"],
                    ["Cash-shortage warning", "Any day the expected balance is below LKR 0"],
                  ]} />
                </CardContent>
              </Card>
            </div>
          )}
        </TabsContent>

        <TabsContent value="bank" className="grid gap-4">
          {readOnly}
          {failed(accounts.error, loadAccounts)}
          {!accounts.value && !accounts.error && <LoadingCard>Loading the bank account…</LoadingCard>}
          {accounts.value && (
            <Card>
              <CardHeader>
                <CardTitle>Company current account</CardTitle>
                <CardDescription>The MVP supports one company current account. FlowLedger only reads its balance and transactions — it never makes payments.</CardDescription>
              </CardHeader>
              <CardContent>
                {account ? (
                  <Details items={[
                    ["Bank", account.bankName], ["Account name", account.accountName],
                    ["Account number", <span className="font-mono">{account.accountNumberLast4 ? `•••• ${account.accountNumberLast4}` : "—"}</span>],
                    ["Account type", "Current Account"], ["Currency", account.currency],
                    ["Connection", MODE_LABEL[account.integrationMode] ?? account.integrationMode],
                    ["Status", <StatusBadge status="Active" />],
                  ]} />
                ) : (
                  <div className="grid justify-items-start gap-3 text-sm">
                    <p className="text-muted-foreground">No current account set up yet. The forecast needs one, with an imported statement for its balance.</p>
                    <Button size="sm" disabled={!isAdmin} onClick={() => setBankOpen(true)}><Plus />Set up current account</Button>
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {isAdmin && (
          <TabsContent value="access" className="grid gap-4">
            {failed(changes.error, loadChanges)}
            {!changes.value && !changes.error && <LoadingCard>Loading access changes…</LoadingCard>}
            {changes.value && (
              <Card>
                <CardHeader>
                  <CardTitle>Access changes</CardTitle>
                  <CardDescription>Users added, roles and statuses changed, and company details edited — the latest 200, newest first.</CardDescription>
                </CardHeader>
                <DataTable<ApiAccessChange>
                  rows={changes.value}
                  rowKey={(r) => r.id}
                  maxHeight="none"
                  empty="No user, role or company changes yet."
                  columns={[
                    { key: "at", header: "When", cell: (r) => <span className="whitespace-nowrap">{when(r.occurredAt)}</span> },
                    { key: "by", header: "By", cell: (r) => r.actorName ?? "—" },
                    { key: "action", header: "Action", cell: (r) => ACTION_LABEL[r.action] ?? r.action },
                    { key: "user", header: "User or setting", cell: (r) => (r.field ? FIELD_LABEL[r.field] ?? r.field : r.targetName ?? "—") },
                    { key: "from", header: "From", cell: (r) => <span className="text-muted-foreground">{changeValue(r.from) ?? "—"}</span> },
                    { key: "to", header: "To", cell: (r) => changeValue(r.to) ?? "—" },
                  ]}
                />
              </Card>
            )}
          </TabsContent>
        )}
      </Tabs>

      {cid && (
        <>
          <CreateUserDialog companyId={cid} open={createOpen} onOpenChange={setCreateOpen} onCreated={() => { loadMembers(); loadChanges() }} />
          {details.value && <EditCompanyDialog company={details.value} open={editOpen} onOpenChange={setEditOpen} onSaved={(c) => { setDetails({ value: c, error: null }); loadChanges() }} />}
          <BankAccountDialog companyId={cid} open={bankOpen} onOpenChange={setBankOpen} onCreated={() => { loadAccounts(); reload("bank") }} />
        </>
      )}
    </>
  )
}

function Details({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[minmax(140px,40%)_1fr] gap-x-4 gap-y-2 text-[13px]">
      {items.map(([k, v]) => <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="font-medium">{v}</dd></div>)}
    </dl>
  )
}

/** Field errors of a 400 answer, or the whole message under `fallback`. */
function fieldErrors(e: unknown, fallback: string): Record<string, string> {
  if (e instanceof ApiError && Object.keys(e.fieldErrors).length) return e.fieldErrors
  return { [fallback]: errorMessage(e) }
}

function CreateUserDialog({ companyId, open, onOpenChange, onCreated }: {
  companyId: string; open: boolean; onOpenChange: (o: boolean) => void; onCreated: () => void
}) {
  const [name, setName] = useState("")
  const [email, setEmail] = useState("")
  const [role, setRole] = useState<CompanyRole>("CASHIER")
  const [password, setPassword] = useState("")
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const reset = () => { setName(""); setEmail(""); setRole("CASHIER"); setPassword(""); setErrors({}) }

  async function submit() {
    setBusy(true)
    try {
      const { membership, accountCreated } = await flowledger.createMembership(companyId, {
        email: email.trim(), fullName: name.trim(), companyRole: role, temporaryPassword: password || null,
      })
      toast.success(`${membership.fullName} added as ${ROLE_NAME[role]}`, {
        description: accountCreated
          ? "Share the temporary password with them privately. They can sign in now."
          : "This email already had a FlowLedger account; it keeps its own name and password.",
      })
      onOpenChange(false); reset(); onCreated()
    } catch (e) {
      setErrors(fieldErrors(e, "email"))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) reset() }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Create user</DialogTitle><DialogDescription>The user is added as Active and will only see this company's data.</DialogDescription></DialogHeader>
        <Field label="Name" htmlFor="new-name" error={errors.fullName}><Input id="new-name" placeholder="Full name" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Work email" htmlFor="new-email" error={errors.email}><Input id="new-email" type="email" placeholder="name@company.lk" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
        <Field label="Role" help={ROLE_HELP[role]} error={errors.companyRole}>
          <Select value={role} onValueChange={(v) => setRole(v as CompanyRole)}>
            <SelectTrigger className="w-full" aria-label="Role"><SelectValue /></SelectTrigger>
            <SelectContent>{COMPANY_ROLES.map((r) => <SelectItem key={r} value={r}>{ROLE_NAME[r]}</SelectItem>)}</SelectContent>
          </Select>
        </Field>
        <Field label="Temporary password" htmlFor="new-password" error={errors.temporaryPassword}
          help="At least 12 characters. Needed for a new account; an existing FlowLedger account keeps its own password.">
          <Input id="new-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={busy || !name.trim() || !email.trim()} onClick={submit}>Create user</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EditCompanyDialog({ company, open, onOpenChange, onSaved }: {
  company: ApiCompany; open: boolean; onOpenChange: (o: boolean) => void; onSaved: (c: ApiCompany) => void
}) {
  const [name, setName] = useState(company.name)
  const [reg, setReg] = useState(company.registrationNumber ?? "")
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (open) { setName(company.name); setReg(company.registrationNumber ?? ""); setErrors({}) } }, [open, company])

  async function submit() {
    setBusy(true)
    try {
      const saved = await flowledger.updateCompany(company.id, { name: name.trim(), registrationNumber: reg.trim() || null })
      toast.success("Company details saved", { description: "Recorded in Access changes" })
      onOpenChange(false); onSaved(saved)
    } catch (e) {
      setErrors(fieldErrors(e, "name"))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>Edit company settings</DialogTitle><DialogDescription>Changes apply to the whole company. Currency and time zone are fixed for the MVP.</DialogDescription></DialogHeader>
        <Field label="Company name" htmlFor="co-name" error={errors.name}><Input id="co-name" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Business registration no." htmlFor="co-reg" error={errors.registrationNumber} help="Optional">
          <Input id="co-reg" value={reg} onChange={(e) => setReg(e.target.value)} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={busy || !name.trim()} onClick={submit}>Save changes</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function BankAccountDialog({ companyId, open, onOpenChange, onCreated }: {
  companyId: string; open: boolean; onOpenChange: (o: boolean) => void; onCreated: () => void
}) {
  const [bankName, setBankName] = useState("")
  const [accountName, setAccountName] = useState("")
  const [last4, setLast4] = useState("")
  const [mode, setMode] = useState<IntegrationMode>("STATEMENT_IMPORT")
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const reset = () => { setBankName(""); setAccountName(""); setLast4(""); setMode("STATEMENT_IMPORT"); setErrors({}) }

  async function submit() {
    if (last4 && !/^\d{4}$/.test(last4)) return setErrors({ accountNumberLast4: "Enter exactly the last 4 digits." })
    setBusy(true)
    try {
      await flowledger.createBankAccount(companyId, {
        bankName: bankName.trim(), accountName: accountName.trim(), accountType: "CURRENT", accountNumberLast4: last4 || null, integrationMode: mode,
      })
      toast.success("Current account set up")
      onOpenChange(false); reset(); onCreated()
    } catch (e) {
      setErrors(fieldErrors(e, "bankName"))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) reset() }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Set up current account</DialogTitle><DialogDescription>Only the last four digits of the account number are stored.</DialogDescription></DialogHeader>
        <Field label="Bank" htmlFor="bank-name" error={errors.bankName}><Input id="bank-name" placeholder="e.g. Commercial Bank" value={bankName} onChange={(e) => setBankName(e.target.value)} /></Field>
        <Field label="Account name" htmlFor="bank-account" error={errors.accountName}><Input id="bank-account" value={accountName} onChange={(e) => setAccountName(e.target.value)} /></Field>
        <Field label="Last 4 digits of the account number" htmlFor="bank-last4" error={errors.accountNumberLast4} help="Optional">
          <Input id="bank-last4" inputMode="numeric" maxLength={4} className="font-mono" value={last4} onChange={(e) => setLast4(e.target.value.replace(/\D/g, ""))} />
        </Field>
        <Field label="Connection" error={errors.integrationMode}>
          <Select value={mode} onValueChange={(v) => setMode(v as IntegrationMode)}>
            <SelectTrigger className="w-full" aria-label="Connection"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="STATEMENT_IMPORT">{MODE_LABEL.STATEMENT_IMPORT}</SelectItem>
              <SelectItem value="MANUAL">{MODE_LABEL.MANUAL}</SelectItem>
            </SelectContent>
          </Select>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={busy || !bankName.trim() || !accountName.trim()} onClick={submit}>Set up account</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
