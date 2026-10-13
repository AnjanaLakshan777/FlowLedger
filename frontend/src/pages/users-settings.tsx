import { useMemo, useState } from "react"
import { useSearchParams } from "react-router-dom"
import { AlertTriangle, Check, Lock, MoreHorizontal, Pencil, Plus, Settings2, ShieldAlert, XCircle } from "lucide-react"
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
import { Field } from "@/components/app/field"
import { PageHeader } from "@/components/app/page-header"
import { SearchInput } from "@/components/app/search-input"
import { StateDot } from "@/components/app/state-dot"
import { StatusBadge } from "@/components/app/status-badge"
import { bankAccount, settings } from "@/data"
import type { AccessChange, Role, Setting, User } from "@/data/types"
import { requiresBackend } from "@/lib/demo"
import { fmt, initials } from "@/lib/format"
import { can, deactivateError, PERMISSION_LABEL, ROLE_DESCRIPTION, ROLE_LABEL, ROLES, roleChangeError } from "@/lib/permissions"

export default function UsersSettingsPage() {
  const { users, currentUser, accessLog, allow, actions } = useAppState()
  const [params] = useSearchParams()
  const [tab, setTab] = useState(params.get("tab") ?? "users")
  const [q, setQ] = useState("")
  const [inviteOpen, setInviteOpen] = useState(false)
  const [editing, setEditing] = useState<string | null>(null)
  // System Admin only (the page itself also requires page.users-settings)
  const canUsers = allow("users.manage"), canRoles = allow("roles.assign"), isAdmin = allow("settings.manage"), canAudit = allow("audit.security")
  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return users.filter((u) => !s || `${u.name} ${u.email} ${ROLE_LABEL[u.role]}`.toLowerCase().includes(s))
  }, [users, q])
  const sections = [...new Set(settings.map((s) => s.section))]
  const changeRole = (u: User, r: Role) => {
    const err = actions.changeRole(u.user_id, r)
    if (err) toast.error(err)
    else toast.success(`${u.name} is now ${ROLE_LABEL[r]}`, { description: "Recorded in Access changes" })
  }
  const deactivate = (u: User) => {
    const err = actions.deactivateUser(u.user_id)
    if (err) toast.error(err)
    else toast(`${u.name} deactivated`, { description: "Recorded in Access changes" })
  }

  const readOnly = !isAdmin && (
    <Alert><Lock /><AlertTitle>View only</AlertTitle><AlertDescription>Only a System Admin can manage users, roles and settings.</AlertDescription></Alert>
  )

  return (
    <>
      <PageHeader
        title="Users & Settings"
        description="Who can use FlowLedger, what each role can do, and company-wide settings. Users only ever see their own company's financial records."
        actions={tab === "users" && canUsers && <Button onClick={() => setInviteOpen(true)}><Plus />Create user</Button>}
      />
      <Alert>
        <ShieldAlert />
        <AlertTitle>Mock authentication</AlertTitle>
        <AlertDescription>
          Sign-in and roles are simulated in this browser tab. These checks only control what the screen shows — the backend APIs must enforce the same permissions.
        </AlertDescription>
      </Alert>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="max-w-full flex-wrap">
          <TabsTrigger value="users">Users</TabsTrigger>
          <TabsTrigger value="roles">Roles & permissions</TabsTrigger>
          <TabsTrigger value="company">Company</TabsTrigger>
          <TabsTrigger value="bank">Bank account</TabsTrigger>
          {canAudit && <TabsTrigger value="access">Access changes</TabsTrigger>}
        </TabsList>

        <TabsContent value="users" className="grid gap-4">
          {readOnly}
          <Card>
            <CardContent><SearchInput value={q} onChange={setQ} placeholder="Search name, email or role" /></CardContent>
            <DataTable<User>
              rows={rows}
              rowKey={(r) => r.user_id}
              defaultSort={{ key: "name", dir: "asc" }}
              maxHeight="none"
              columns={[
                {
                  key: "name", header: "Name", sortValue: (r) => r.name, cell: (r) => (
                    <div className="flex items-center gap-2.5">
                      <span className="bg-primary/10 text-primary dark:text-chart-2 grid size-7 shrink-0 place-items-center rounded-full text-[11px] font-semibold">{initials(r.name)}</span>
                      <CellStack main={<b className="font-semibold">{r.name}{r.user_id === currentUser?.user_id && <span className="text-muted-foreground font-normal"> (you)</span>}</b>} sub={r.email} />
                    </div>
                  ),
                },
                { key: "role", header: "Role", sortValue: (r) => r.role, cell: (r) => <Badge variant={r.role === "SYSTEM_ADMIN" ? "secondary" : "outline"}>{ROLE_LABEL[r.role]}</Badge> },
                { key: "status", header: "Status", sortValue: (r) => r.status, cell: (r) => <StatusBadge status={r.status} /> },
                { key: "created", header: "Created", sortValue: (r) => r.created_at, cell: (r) => <span className="text-muted-foreground whitespace-nowrap">{fmt.date(r.created_at)}</span> },
                {
                  key: "act", header: "", cell: (r) => {
                    if (!currentUser || !(canRoles || canUsers)) return null
                    // you can't change your own role or account — another System Admin must
                    if (r.user_id === currentUser.user_id) return <span className="text-muted-foreground text-xs">Your account</span>
                    const offStatus = deactivateError(currentUser, r, users)
                    const lastAdmin = roleChangeError(currentUser, r, r.role === "CASHIER" ? "ACCOUNTANT" : "CASHIER", users)
                    return (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" aria-label={`Actions for ${r.name}`}><MoreHorizontal /></Button></DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-64">
                          <DropdownMenuLabel>Role</DropdownMenuLabel>
                          <DropdownMenuRadioGroup value={r.role} onValueChange={(v) => changeRole(r, v as Role)}>
                            {ROLES.map((x) => (
                              <DropdownMenuRadioItem key={x} value={x} disabled={x !== r.role && !!roleChangeError(currentUser, r, x, users)}>{ROLE_LABEL[x]}</DropdownMenuRadioItem>
                            ))}
                          </DropdownMenuRadioGroup>
                          {lastAdmin && <DropdownMenuLabel className="text-muted-foreground text-xs font-normal">{lastAdmin}</DropdownMenuLabel>}
                          <DropdownMenuSeparator />
                          {r.status === "Invited" && <DropdownMenuItem onSelect={() => requiresBackend("Resending the invitation")}>Resend invite</DropdownMenuItem>}
                          <DropdownMenuItem variant="destructive" disabled={!!offStatus} onSelect={() => deactivate(r)}>
                            <XCircle />Deactivate
                          </DropdownMenuItem>
                          {offStatus && r.status !== "Deactivated" && <DropdownMenuLabel className="text-muted-foreground text-xs font-normal">{offStatus}</DropdownMenuLabel>}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    )
                  },
                },
              ]}
            />
          </Card>
        </TabsContent>

        <TabsContent value="roles">
          <Card>
            <CardHeader><CardTitle>What each role can do</CardTitle><CardDescription>Roles are assigned per user by a System Admin. Permissions come from the role.</CardDescription></CardHeader>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow><TableHead>Permission</TableHead>{ROLES.map((r) => <TableHead key={r} className="text-center">{ROLE_LABEL[r]}</TableHead>)}</TableRow>
                </TableHeader>
                <TableBody>
                  {PERMISSION_LABEL.map(([perm, label]) => (
                    <TableRow key={perm}>
                      <TableCell>{perm.startsWith("page.") ? <>Open <b className="font-medium">{label}</b></> : label}</TableCell>
                      {ROLES.map((r) => (
                        <TableCell key={r} className="text-center">
                          {can(r, perm) ? <Check className="text-primary dark:text-chart-2 mx-auto size-4" aria-label="Allowed" /> : <span className="text-muted-foreground" aria-label="Not allowed">—</span>}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <CardContent className="text-muted-foreground grid gap-1 text-xs">
              {ROLES.map((r) => <p key={r}><b className="text-foreground font-medium">{ROLE_LABEL[r]}:</b> {ROLE_DESCRIPTION[r]}</p>)}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="company" className="grid gap-4">
          {readOnly}
          <div className="grid gap-4 xl:grid-cols-2">
            {sections.map((sec) => (
              <Card key={sec}>
                <CardHeader>
                  <CardTitle>{sec}</CardTitle>
                  <CardAction>{sec === "Company" ? <Button variant="outline" size="sm" disabled={!isAdmin} onClick={() => setEditing(sec)}><Pencil />Edit</Button> : <span className="text-muted-foreground text-xs">Fixed for the MVP</span>}</CardAction>
                </CardHeader>
                <CardContent>
                  <dl className="grid grid-cols-[minmax(140px,45%)_1fr] gap-x-4 gap-y-2 text-[13px]">
                    {settings.filter((s) => s.section === sec).map((s) => (
                      <div key={s.key} className="contents"><dt className="text-muted-foreground">{s.label}</dt><dd className="font-medium">{s.value}</dd></div>
                    ))}
                  </dl>
                </CardContent>
              </Card>
            ))}
          </div>
        </TabsContent>

        <TabsContent value="bank" className="grid gap-4">
          {readOnly}
          <Card>
            <CardHeader>
              <CardTitle>Company current account</CardTitle>
              <CardDescription>The MVP supports one company current account. FlowLedger only reads its balance and transactions — it never makes payments. No bank is connected in this demonstration.</CardDescription>
              {bankAccount && <CardAction><Button size="sm" variant="outline" disabled={!isAdmin} onClick={() => requiresBackend("Configuring the bank integration")}><Settings2 />Configure</Button></CardAction>}
            </CardHeader>
            <CardContent>
              {bankAccount ? (
                <dl className="grid grid-cols-[minmax(140px,35%)_1fr] gap-x-4 gap-y-2 text-[13px]">
                  {([
                    ["Bank", bankAccount.bank], ["Account name", bankAccount.account_name], ["Account number", <span className="font-mono">{bankAccount.account_masked}</span>],
                    ["Account type", "Current Account"], ["Connection", bankAccount.integration],
                    ["Last successful sync", <StateDot tone={bankAccount.sync_status === "Synced" ? "ok" : "error"}>{fmt.dateTimeFull(bankAccount.last_sync)}</StateDot>],
                    ["Status", bankAccount.sync_status === "Synced" ? <StatusBadge status="Synced" /> : <Badge variant="destructive-soft"><AlertTriangle />Sync failed</Badge>],
                  ] as const).map(([k, v]) => (
                    <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="font-medium">{v}</dd></div>
                  ))}
                </dl>
              ) : (
                <div className="grid justify-items-start gap-3 text-sm">
                  <p className="text-muted-foreground">No current account set up yet.</p>
                  <Button size="sm" disabled={!isAdmin} onClick={() => requiresBackend("Setting up the company current account")}><Plus />Set up current account</Button>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
        {canAudit && (
          <TabsContent value="access">
            <Card>
              <CardHeader>
                <CardTitle>Access changes</CardTitle>
                <CardDescription>Users created, roles changed and users deactivated in this session. In the real system these come from the backend audit log.</CardDescription>
              </CardHeader>
              <DataTable<AccessChange>
                rows={accessLog}
                rowKey={(r) => r.id}
                maxHeight="none"
                empty="No user, role or status changes yet."
                columns={[
                  { key: "at", header: "When", cell: (r) => <span className="whitespace-nowrap">{fmt.dateTime(r.at)}</span> },
                  { key: "by", header: "By", cell: (r) => r.by },
                  { key: "action", header: "Action", cell: (r) => r.action },
                  { key: "user", header: "User", cell: (r) => r.target },
                  { key: "from", header: "From", cell: (r) => <span className="text-muted-foreground">{r.from || "—"}</span> },
                  { key: "to", header: "To", cell: (r) => r.to },
                ]}
              />
            </Card>
          </TabsContent>
        )}
      </Tabs>

      <InviteDialog open={inviteOpen} onOpenChange={setInviteOpen} />
      <EditSettingsDialog section={editing} items={settings.filter((s) => s.section === editing)} onClose={() => setEditing(null)} />
    </>
  )
}

function InviteDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const { actions } = useAppState()
  const [name, setName] = useState("")
  const [email, setEmail] = useState("")
  const [role, setRole] = useState<Role>("CASHIER")
  const [err, setErr] = useState<string | null>(null)
  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) { setName(""); setEmail(""); setRole("CASHIER"); setErr(null) } }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Create user</DialogTitle><DialogDescription>The user is added as Invited. They will only see this company's data.</DialogDescription></DialogHeader>
        <Field label="Name" htmlFor="inv-name"><Input id="inv-name" placeholder="Full name" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Work email" htmlFor="inv-email"><Input id="inv-email" type="email" placeholder="name@lotuslane.lk" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
        <Field label="Role" help={ROLE_DESCRIPTION[role]} error={err}>
          <Select value={role} onValueChange={(v) => setRole(v as Role)}>
            <SelectTrigger className="w-full" aria-label="Role"><SelectValue /></SelectTrigger>
            <SelectContent>{ROLES.map((r) => <SelectItem key={r} value={r}>{ROLE_LABEL[r]}</SelectItem>)}</SelectContent>
          </Select>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => {
            const e = actions.createUser({ name, email, role })
            if (e) return setErr(e)
            onOpenChange(false); setName(""); setEmail(""); setErr(null)
            toast.success(`${name.trim()} created as ${ROLE_LABEL[role]}`, { description: "Status Invited. Sending the invitation email needs the backend." })
          }}>Create user</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EditSettingsDialog({ section, items, onClose }: { section: string | null; items: Setting[]; onClose: () => void }) {
  return (
    <Dialog open={!!section} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>Edit {section?.toLowerCase()} settings</DialogTitle><DialogDescription>Changes apply to the whole company.</DialogDescription></DialogHeader>
        <div className="grid gap-3.5">
          {items.map((s) => <Field key={s.key} label={s.label} htmlFor={`set-${s.key}`}><Input id={`set-${s.key}`} defaultValue={s.value} /></Field>)}
          <Field label="Reason for change" htmlFor="set-reason"><Input id="set-reason" placeholder="Optional" /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={() => { onClose(); requiresBackend("Saving company settings", "No settings were changed.") }}>Save changes</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
