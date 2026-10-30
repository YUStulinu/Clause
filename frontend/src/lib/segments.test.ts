import { describe, expect, it } from 'vitest'
import { byKind, segments } from './segments'

describe('segments', () => {
  const text = 'The Tenant pays rent. Late payment costs 0.1% a day.'

  it('marks a quote exactly where it is', () => {
    const s = segments(text, [{ id: 'f1', start: 22, end: 52 }])
    expect(s.map((x) => x.text)).toEqual(['The Tenant pays rent. ', 'Late payment costs 0.1% a day.'])
    expect(s[1].marks).toEqual(['f1'])
    expect(s[0].marks).toEqual([])
  })

  it('handles overlapping quotes', () => {
    const s = segments(text, [{ id: 'a', start: 0, end: 21 }, { id: 'b', start: 4, end: 10 }])
    expect(s.map((x) => [x.text, x.marks])).toEqual([
      ['The ', ['a']],
      ['Tenant', ['a', 'b']],
      [' pays rent.', ['a']],
      [' Late payment costs 0.1% a day.', []],
    ])
  })

  it('cuts only inside the window, and puts the window back together', () => {
    const s = segments(text, [{ id: 'f', start: 0, end: 40 }], 10, 30)
    expect(s.map((x) => x.text).join('')).toBe(text.slice(10, 30))
    expect(s.every((x) => x.marks.includes('f'))).toBe(true)
  })

  it('ignores quotes that were not found (start -1)', () => {
    expect(segments(text, [{ id: 'x', start: -1, end: -1 }])).toHaveLength(1)
  })
})

describe('byKind', () => {
  it('groups in the given order and drops empty groups', () => {
    const g = byKind([{ kind: 'penalty' }, { kind: 'payment' }, { kind: 'penalty' }], ['payment', 'penalty', 'termination'])
    expect(g.map(([k, v]) => [k, v.length])).toEqual([['payment', 1], ['penalty', 2]])
  })
})
