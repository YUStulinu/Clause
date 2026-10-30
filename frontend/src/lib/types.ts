// What the backend sends (see backend/src/main/java/com/clause/web/ContractController.java).

export interface Clause {
  id: string
  number: string
  title: string
  level: number
  start: number
  end: number
}

export interface Contract {
  id: string
  name: string
  language: 'en' | 'ro'
  text: string
  clauses: Clause[]
  disclaimer: string
}

export type Kind = 'payment' | 'penalty' | 'termination' | 'obligation' | 'liability' | 'unusual'
export type Status = 'verified' | 'unsupported' | 'unanchored'

export interface Finding {
  clause: string
  kind: Kind
  party: string
  summary: string
  quote: string
  why: string
  status: Status
  start: number
  end: number
  checks: Record<string, string | number>
}

export interface Analysis {
  findings: Finding[]
  kinds: Kind[]
  counts: Record<Status, number>
  seconds: number
  disclaimer: string
}

export type PairKind = 'SAME' | 'COSMETIC' | 'MODIFIED' | 'ADDED' | 'REMOVED'

export interface Run {
  kind: 'SAME' | 'DELETED' | 'INSERTED'
  text: string
}

export interface Entry {
  id: string
  kind: PairKind
  a: Clause | null
  b: Clause | null
  similarity: number
  moved: boolean
  diff: Run[]
  facts: { before: string | null; after: string | null }[]
  negationChanged: boolean
  modalityChanged: boolean
  onlyBefore: string[]
  onlyAfter: string[]
  meaning: { meaning: 'same' | 'changed'; reasons: string[]; explanation: string } | null
}

export interface Comparison {
  a: { id: string; name: string; text: string }
  b: { id: string; name: string; text: string }
  counts: Record<string, number>
  entries: Entry[]
  note: string | null
  disclaimer: string
}
