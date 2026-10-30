import type { Analysis, Comparison, Contract } from './types'

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, init)
  const body = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(body.error ?? body.message ?? `${r.status} ${r.statusText}`)
  return body as T
}

export const api = {
  health: () => call<{ backend: string; analysis: string }>('/api/health'),
  upload(file: File): Promise<Contract> {
    const form = new FormData()
    form.append('file', file)
    return call('/api/contracts', { method: 'POST', body: form })
  },
  contract: (id: string) => call<Contract>(`/api/contracts/${id}`),
  analyze: (id: string, language = '') =>
    call<Analysis>(`/api/contracts/${id}/analysis?language=${language}`, { method: 'POST' }),
  compare: (a: string, b: string) => call<Comparison>(`/api/compare?a=${a}&b=${b}`),
}
