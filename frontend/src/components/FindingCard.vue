<script setup lang="ts">
import { computed } from 'vue'
import type { Finding } from '../lib/types'

const props = defineProps<{ finding: Finding; selected: boolean; text: string }>()
defineEmits<{ select: [] }>()

// The quote as it is in the contract (the model's copy may have been repaired to the real text).
const quote = computed(() => (props.finding.start >= 0 ? props.text.slice(props.finding.start, props.finding.end) : props.finding.quote))

const why = computed(() => {
  const c = props.finding.checks
  const out: string[] = []
  if (c.anchor === 'missing') out.push('its quote is not in the contract')
  if (c.anchor === 'repaired') out.push('quote repaired to the exact text')
  if (typeof c.numbers === 'string' && c.numbers !== 'ok') out.push(`numbers ${c.numbers.replace('missing', 'not in the quote:')}`)
  if (typeof c.names === 'string' && c.names !== 'ok') out.push(`names ${c.names}`)
  if (typeof c.entailment === 'number' && c.entailment < 0.5) out.push(`the quote does not clearly say this (${Math.round(c.entailment * 100)}%)`)
  return out
})
</script>

<template>
  <div class="card" :class="[`kind-${finding.kind}`, finding.status, { selected }]" @click="$emit('select')">
    <div class="summary">{{ finding.summary }}</div>
    <div v-if="finding.why" class="why">Why it stands out: {{ finding.why }}</div>
    <blockquote>“{{ quote }}”</blockquote>
    <div class="meta">
      <span v-if="finding.party && finding.party !== 'both'">{{ finding.party }}</span>
      <span v-if="finding.status === 'verified'" class="ok">✓ matches its quote</span>
      <span v-else class="bad">not confirmed: {{ why.join('; ') || finding.status }}</span>
      <span v-if="finding.status === 'verified' && finding.checks.anchor === 'repaired'" class="muted">quote repaired</span>
    </div>
  </div>
</template>

<style scoped>
.card {
  background: var(--paper);
  border: 1px solid var(--line);
  border-left: 4px solid var(--k-ink);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
}
.card.selected { box-shadow: 0 0 0 2px var(--k-ink); }
.card.unsupported, .card.unanchored { border-left-style: dashed; opacity: 0.8; }
.summary { font-weight: 600; }
.why { font-size: 13px; color: var(--k-ink); margin-top: 2px; }
blockquote {
  margin: 6px 0;
  padding: 4px 8px;
  background: var(--k);
  border-radius: 4px;
  font-family: Georgia, serif;
  font-size: 13px;
  color: #333;
}
.meta { display: flex; gap: 10px; font-size: 12px; color: var(--muted); flex-wrap: wrap; }
.ok { color: var(--ok); }
.bad { color: var(--bad); }
</style>
