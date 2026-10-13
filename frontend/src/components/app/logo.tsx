export function Logo({ className = "" }: { className?: string }) {
  return (
    <div className={`bg-primary text-primary-foreground grid size-7 shrink-0 place-items-center rounded-md ${className}`}>
      <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M3 12h4l3-9 4 18 3-9h4" />
      </svg>
    </div>
  )
}
