import type { ImportRow } from "./types"
import importRowsJson from "./import_rows.json"

/** Rows of the uploaded historical file (kept separate so it only loads on the import screen). */
export const importRows = importRowsJson as ImportRow[]
