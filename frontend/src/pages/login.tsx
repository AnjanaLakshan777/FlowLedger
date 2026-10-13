import { useState, type FormEvent } from "react"
import { useNavigate } from "react-router-dom"
import { Eye, EyeOff, FileText, Info, Landmark, ShieldCheck, TrendingUp } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Separator } from "@/components/ui/separator"
import { Field } from "@/components/app/field"
import { Logo } from "@/components/app/logo"
import { useAppState } from "@/components/app/app-state"
import { homePage, ROLE_LABEL } from "@/lib/permissions"

export default function LoginPage() {
  const { actions, users } = useAppState()
  const navigate = useNavigate()
  const [email, setEmail] = useState("nadeesha@lotuslane.lk")
  const [password, setPassword] = useState("flowledger-demo")
  const [show, setShow] = useState(false)
  const [remember, setRemember] = useState(true)
  const [forgot, setForgot] = useState(false)
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({})

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const next = {
      email: /.+@.+\..+/.test(email) ? undefined : "Enter a valid work email address.",
      password: password.length >= 8 ? undefined : "Password must be at least 8 characters.",
    }
    setErrors(next)
    if (next.email || next.password) return
    signInAs(email)
  }
  /** Mock authentication: any 8+ character password works; the email picks the demo user and therefore the role. */
  const signInAs = (address: string) => {
    const err = actions.signIn(address)
    if (err) return setErrors({ email: err })
    const u = users.find((x) => x.email.toLowerCase() === address.trim().toLowerCase())
    navigate(`/${homePage(u?.role) ?? ""}`)
  }
  const demoUsers = users.filter((u) => u.status === "Active")

  return (
    <div className="grid min-h-svh lg:grid-cols-2">
      <div className="bg-muted hidden flex-col justify-between gap-8 border-r p-10 lg:flex">
        <div className="flex items-center gap-2.5">
          <Logo />
          <div className="leading-tight">
            <div className="text-[15px] font-semibold">FlowLedger</div>
            <div className="text-muted-foreground text-xs">Cash position & forecast</div>
          </div>
        </div>
        <div className="grid gap-6">
          <h2 className="max-w-[22ch] text-2xl leading-tight font-semibold tracking-tight">Know your cash position before the bank does.</h2>
          <ul className="grid max-w-[42ch] gap-3.5">
            {[
              { icon: Landmark, t: "Actual Bank Balance", d: "What the bank has confirmed in your current account, kept separate from what is only expected." },
              { icon: FileText, t: "Every cheque payment tracked", d: "Each future cheque is forecast on its expected clearing date." },
              { icon: TrendingUp, t: "12-week cash-flow forecast", d: "Daily balances that warn you before an account goes negative." },
            ].map(({ icon: Icon, t, d }) => (
              <li key={t} className="text-muted-foreground grid grid-cols-[20px_1fr] gap-2.5 text-[13.5px]">
                <Icon className="text-primary mt-0.5 size-[18px]" />
                <div><b className="text-foreground block font-medium">{t}</b>{d}</div>
              </li>
            ))}
          </ul>
        </div>
        <div className="text-muted-foreground text-xs">© 2026 FlowLedger · Colombo, Sri Lanka</div>
      </div>
      <div className="grid place-items-center px-6 py-10">
        <form onSubmit={submit} className="grid w-full max-w-[380px] gap-5" noValidate>
          <div className="grid gap-1.5">
            <div className="mb-2 lg:hidden"><Logo /></div>
            <h1 className="text-[22px] font-semibold tracking-tight">Sign in</h1>
            <p className="text-muted-foreground text-sm">Use the email your company admin invited.</p>
          </div>
          {forgot && (
            <Alert variant="info">
              <Info />
              <AlertTitle>Password reset isn't available yet</AlertTitle>
              <AlertDescription>It needs the authentication service. No email was sent.</AlertDescription>
            </Alert>
          )}
          <Field label="Work email" htmlFor="email" error={errors.email}>
            <Input id="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} aria-invalid={!!errors.email || undefined} />
          </Field>
          <Field
            label="Password"
            htmlFor="password"
            error={errors.password}
            labelAside={<button type="button" className="text-primary dark:text-chart-2 text-[13px] font-medium hover:underline" onClick={() => setForgot(true)}>Forgot password?</button>}
          >
            <div className="relative">
              <Input id="password" type={show ? "text" : "password"} autoComplete="current-password" className="pr-10" value={password} onChange={(e) => setPassword(e.target.value)} aria-invalid={!!errors.password || undefined} />
              <Button type="button" variant="ghost" size="icon-sm" className="absolute top-1/2 right-0.5 -translate-y-1/2" onClick={() => setShow((s) => !s)} aria-label={show ? "Hide password" : "Show password"}>
                {show ? <EyeOff /> : <Eye />}
              </Button>
            </div>
          </Field>
          <div className="flex items-center gap-2">
            <Checkbox id="remember" checked={remember} onCheckedChange={(v) => setRemember(v === true)} />
            <Label htmlFor="remember" className="font-normal">Keep me signed in on this device</Label>
          </div>
          <Button type="submit" className="w-full">Sign in</Button>
          <Separator />
          <p className="text-muted-foreground flex items-start justify-center gap-1.5 text-center text-xs">
            <ShieldCheck className="mt-px size-3.5 shrink-0" />
            Mock authentication — no real accounts or passwords yet. The authentication service will replace this screen's logic.
          </p>
          <div className="grid gap-2" aria-label="Demo accounts">
            <p className="text-muted-foreground text-xs font-medium">Demo accounts (mock sign-in)</p>
            {demoUsers.map((u) => (
              <Button key={u.user_id} type="button" variant="outline" size="sm" className="justify-between" onClick={() => signInAs(u.email)}>
                <span className="truncate">{u.name}</span><span className="text-muted-foreground text-xs">{ROLE_LABEL[u.role]}</span>
              </Button>
            ))}
          </div>
        </form>
      </div>
    </div>
  )
}
