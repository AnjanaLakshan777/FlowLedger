import { Fragment, useMemo, useState, type ReactNode } from "react"
import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { cn } from "@/lib/utils"

export interface Column<T> {
  key: string
  header: ReactNode
  /** Right-align and use tabular numbers. */
  numeric?: boolean
  cell: (row: T) => ReactNode
  /** Value used for sorting; omit to make the column unsortable. */
  sortValue?: (row: T) => string | number | null
  className?: string | ((row: T) => string)
  headClassName?: string
}

/** Compact, sortable financial table with a sticky header. */
export function DataTable<T>({
  columns, rows, rowKey, defaultSort, maxHeight = 520, empty = "No records match these filters.", rowClassName, onRowClick, groupBy, footer, id,
}: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  defaultSort?: { key: string; dir: "asc" | "desc" }
  maxHeight?: number | "none"
  empty?: ReactNode
  rowClassName?: (row: T) => string
  onRowClick?: (row: T) => void
  groupBy?: (row: T) => string
  footer?: (rows: T[]) => ReactNode
  id?: string
}) {
  const [sort, setSort] = useState(defaultSort ?? null)
  const sorted = useMemo(() => {
    if (!sort) return rows
    const col = columns.find((c) => c.key === sort.key)
    if (!col?.sortValue) return rows
    const sv = col.sortValue
    return [...rows].sort((a, b) => {
      const x = sv(a) ?? "", y = sv(b) ?? ""
      const r = typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y))
      return sort.dir === "asc" ? r : -r
    })
  }, [rows, sort, columns])

  const toggle = (c: Column<T>) => {
    if (!c.sortValue) return
    setSort((s) => (s?.key === c.key ? { key: c.key, dir: s.dir === "asc" ? "desc" : "asc" } : { key: c.key, dir: c.numeric ? "desc" : "asc" }))
  }

  let lastGroup: string | null = null
  return (
    <div id={id}>
      <div className="overflow-auto" style={{ maxHeight: maxHeight === "none" ? undefined : maxHeight }}>
        <Table className="border-separate border-spacing-0">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              {columns.map((c) => (
                <TableHead
                  key={c.key}
                  scope="col"
                  aria-sort={sort?.key === c.key ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}
                  className={cn("bg-card sticky top-0 z-10 border-b", c.numeric && "text-right", c.headClassName)}
                >
                  {c.sortValue ? (
                    <button type="button" onClick={() => toggle(c)} className={cn("hover:text-foreground focus-visible:ring-ring/50 inline-flex items-center gap-1 rounded-sm outline-none focus-visible:ring-[3px]", c.numeric && "flex-row-reverse")}>
                      {c.header}
                      {sort?.key === c.key ? (sort.dir === "asc" ? <ArrowUp className="size-3" /> : <ArrowDown className="size-3" />) : <ArrowUpDown className="size-3 opacity-40" />}
                    </button>
                  ) : c.header}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {sorted.length === 0 && (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={columns.length} className="text-muted-foreground py-10 text-center">{empty}</TableCell>
              </TableRow>
            )}
            {sorted.map((r) => {
              const g = groupBy ? groupBy(r) : null
              const showGroup = g !== null && g !== lastGroup
              lastGroup = g
              return (
                <Fragment key={rowKey(r)}>
                  {showGroup && (
                    <TableRow className="bg-muted hover:bg-muted">
                      <TableCell colSpan={columns.length} className="text-muted-foreground py-1.5 text-xs font-semibold">{g}</TableCell>
                    </TableRow>
                  )}
                  <TableRow
                    className={cn(rowClassName?.(r), onRowClick && "cursor-pointer")}
                    onClick={onRowClick ? (e) => { if (!(e.target as HTMLElement).closest("button,a,input")) onRowClick(r) } : undefined}
                  >
                    {columns.map((c) => (
                      <TableCell
                        key={c.key}
                        className={cn("border-b", c.numeric && "text-right whitespace-nowrap tabular", typeof c.className === "function" ? c.className(r) : c.className)}
                      >
                        {c.cell(r)}
                      </TableCell>
                    ))}
                  </TableRow>
                </Fragment>
              )
            })}
          </TableBody>
        </Table>
      </div>
      {footer && <div className="text-muted-foreground flex flex-wrap items-center justify-between gap-2 border-t px-4 pt-3 text-xs">{footer(sorted)}</div>}
    </div>
  )
}

/** Primary line plus a muted second line inside a cell. */
export function CellStack({ main, sub, className }: { main: ReactNode; sub?: ReactNode; className?: string }) {
  return (
    <div className={cn("min-w-0", className)}>
      <div>{main}</div>
      {sub && <div className="text-muted-foreground text-xs">{sub}</div>}
    </div>
  )
}
