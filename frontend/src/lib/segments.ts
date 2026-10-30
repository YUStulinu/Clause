// Cutting a text into pieces for display: each piece knows which highlights cover it, so a quote
// can be marked exactly where it is, even where quotes overlap.

export interface Mark {
  id: string
  start: number
  end: number
}

export interface Segment {
  start: number
  end: number
  text: string
  marks: string[]
}

/** The text between `from` and `to`, cut wherever a mark starts or ends. */
export function segments(text: string, marks: Mark[], from = 0, to = text.length): Segment[] {
  const cuts = new Set<number>([from, to])
  for (const m of marks) {
    if (m.end <= from || m.start >= to || m.start < 0) continue
    cuts.add(Math.max(from, m.start))
    cuts.add(Math.min(to, m.end))
  }
  const points = [...cuts].sort((a, b) => a - b)
  const out: Segment[] = []
  for (let i = 0; i + 1 < points.length; i++) {
    const s = points[i], e = points[i + 1]
    if (e <= s) continue
    const covering = marks.filter((m) => m.start >= 0 && m.start <= s && m.end >= e).map((m) => m.id)
    out.push({ start: s, end: e, text: text.slice(s, e), marks: covering })
  }
  return out
}

/** Findings grouped by kind, in the given order of kinds. */
export function byKind<T extends { kind: string }>(items: T[], order: string[]): [string, T[]][] {
  const groups = new Map<string, T[]>(order.map((k) => [k, []]))
  for (const it of items) {
    if (!groups.has(it.kind)) groups.set(it.kind, [])
    groups.get(it.kind)!.push(it)
  }
  return [...groups.entries()].filter(([, v]) => v.length > 0)
}
