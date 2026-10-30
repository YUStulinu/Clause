<script setup lang="ts">
import { onMounted, ref } from 'vue'
import CompareView from './components/CompareView.vue'
import ReadView from './components/ReadView.vue'
import { api } from './lib/api'
import type { Contract } from './lib/types'

const tab = ref<'read' | 'compare'>(new URLSearchParams(location.search).has('a') ? 'compare' : 'read')
// The contracts uploaded in this session, for comparing.
const contracts = ref<Contract[]>([])
const service = ref('')

function remember(c: Contract) {
  contracts.value = [c, ...contracts.value.filter((x) => x.id !== c.id)]
}

onMounted(async () => {
  try {
    const h = await api.health()
    service.value = h.analysis === 'up' ? '' : 'The analysis service is not running: contracts can be read and compared, not explained.'
  } catch {
    service.value = 'The backend is not answering.'
  }
})
</script>

<template>
  <header>
    <div class="brand">
      <strong>Clause</strong>
      <span>reads a contract and tells you what it asks of you</span>
    </div>
    <nav>
      <button :class="{ on: tab === 'read' }" @click="tab = 'read'">Read a contract</button>
      <button :class="{ on: tab === 'compare' }" @click="tab = 'compare'">Compare two versions</button>
    </nav>
  </header>
  <div class="notice">
    Not legal advice. Every statement below is checked against the exact words it quotes, but a checker can miss
    things: read the highlighted clauses yourself, and ask a lawyer before signing anything that matters.
  </div>
  <div v-if="service" class="notice down">{{ service }}</div>
  <main>
    <ReadView v-show="tab === 'read'" @uploaded="remember" />
    <CompareView v-show="tab === 'compare'" :contracts="contracts" @uploaded="remember" />
  </main>
</template>

<style scoped>
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: var(--paper);
  border-bottom: 1px solid var(--line);
}
.brand strong { font-size: 22px; color: var(--accent); margin-right: 12px; letter-spacing: 0.5px; }
.brand span { color: var(--muted); }
nav button { margin-left: 8px; border: none; background: none; color: var(--muted); }
nav button.on { color: var(--ink); font-weight: 600; border-bottom: 2px solid var(--accent); border-radius: 0; }
.notice { background: var(--warn-bg); color: var(--warn-ink); padding: 8px 24px; font-size: 14px; border-bottom: 1px solid #f0dfb0; }
.notice.down { background: #fdecea; color: var(--bad); }
main { padding: 16px 24px; }
</style>
