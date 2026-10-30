<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api'
import { byKind } from '../lib/segments'
import type { Analysis, Contract, Finding } from '../lib/types'
import DocumentText from './DocumentText.vue'
import FindingCard from './FindingCard.vue'

const emit = defineEmits<{ uploaded: [c: Contract] }>()

const contract = ref<Contract | null>(null)
const analysis = ref<Analysis | null>(null)
const busy = ref('')
const error = ref('')
const selected = ref<string | null>(null)
const showUnconfirmed = ref(false)
const answerLanguage = ref('')

const KIND_NAMES: Record<string, string> = {
  payment: 'What you pay',
  penalty: 'Penalties',
  termination: 'Ending and renewal',
  obligation: 'Obligations',
  liability: 'Liability',
  unusual: 'Unusual clauses',
}

const findings = computed<(Finding & { id: string })[]>(() =>
  (analysis.value?.findings ?? []).map((f, i) => ({ ...f, id: `f${i}` })),
)
const confirmed = computed(() => findings.value.filter((f) => f.status === 'verified'))
const unconfirmed = computed(() => findings.value.filter((f) => f.status !== 'verified'))
const groups = computed(() => byKind(confirmed.value, analysis.value?.kinds ?? []))
const marks = computed(() =>
  findings.value
    .filter((f) => f.start >= 0 && (f.status === 'verified' || showUnconfirmed.value))
    .map((f) => ({ id: f.id, start: f.start, end: f.end, kind: f.kind, verified: f.status === 'verified' })),
)

async function upload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  error.value = ''
  busy.value = 'Reading the file…'
  analysis.value = null
  try {
    contract.value = await api.upload(file)
    emit('uploaded', contract.value)
    answerLanguage.value = contract.value.language
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    busy.value = ''
  }
}

// A contract already in this session: ?contract=<id> (and &explain to explain it at once).
onMounted(async () => {
  const q = new URLSearchParams(location.search)
  const id = q.get('contract')
  if (!id) return
  try {
    contract.value = await api.contract(id)
    emit('uploaded', contract.value)
    answerLanguage.value = q.get('language') ?? contract.value.language
    if (q.has('explain')) await analyze()
  } catch (err) {
    error.value = (err as Error).message
  }
})

async function analyze() {
  if (!contract.value) return
  error.value = ''
  busy.value = 'Reading the contract clause by clause and checking every statement against its quote… (a minute or two)'
  try {
    analysis.value = await api.analyze(contract.value.id, answerLanguage.value)
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="read">
    <div class="left">
      <div class="bar">
        <label class="upload">
          <input type="file" accept=".pdf,.docx,.txt" @change="upload" />
          <span>{{ contract ? 'Another contract…' : 'Upload a contract (PDF, DOCX or text)' }}</span>
        </label>
        <template v-if="contract">
          <span class="name">{{ contract.name }} · {{ contract.clauses.length }} clauses</span>
        </template>
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <DocumentText v-if="contract" :text="contract.text" :clauses="contract.clauses" :marks="marks" :selected="selected"
                    @select="(id) => (selected = id)" />
      <div v-else class="empty">
        Upload a rental, loan, subscription or employment contract. Clause finds what you have to do, what you pay
        (fees, interest, the annual percentage rate), the penalties, how the contract ends or renews, and anything
        unusual, each with the exact words it comes from. Nothing leaves this computer.
      </div>
    </div>

    <aside v-if="contract">
      <div class="bar">
        <button class="primary" :disabled="!!busy" @click="analyze">{{ analysis ? 'Explain again' : 'Explain this contract' }}</button>
        <select v-model="answerLanguage" title="Language of the explanations">
          <option value="en">in English</option>
          <option value="ro">în română</option>
        </select>
      </div>
      <p v-if="busy" class="busy">{{ busy }}</p>
      <template v-if="analysis">
        <p class="counts">
          {{ analysis.counts.verified }} statements, each matching the words it quotes.
          <template v-if="unconfirmed.length">
            {{ unconfirmed.length }} more could not be confirmed and are set aside.
          </template>
        </p>
        <section v-for="[kind, items] in groups" :key="kind">
          <h3 :class="`kind-${kind}`">{{ KIND_NAMES[kind] ?? kind }}</h3>
          <FindingCard v-for="f in items" :key="f.id" :finding="f" :text="contract.text" :selected="selected === f.id"
                       @select="selected = f.id" />
        </section>
        <section v-if="unconfirmed.length">
          <button class="link" @click="showUnconfirmed = !showUnconfirmed">
            {{ showUnconfirmed ? 'Hide' : 'Show' }} the {{ unconfirmed.length }} statements the checker could not confirm
          </button>
          <template v-if="showUnconfirmed">
            <FindingCard v-for="f in unconfirmed" :key="f.id" :finding="f" :text="contract.text" :selected="selected === f.id"
                         @select="selected = f.id" />
          </template>
        </section>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.read { display: flex; gap: 20px; align-items: flex-start; }
.left { flex: 1 1 60%; min-width: 0; }
aside {
  flex: 0 0 420px;
  position: sticky;
  top: 12px;
  max-height: calc(100vh - 24px);
  overflow-y: auto;
  padding-right: 4px;
}
.bar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.upload input { display: none; }
.upload span { display: inline-block; padding: 6px 12px; border: 1px dashed var(--accent); border-radius: 6px; color: var(--accent); cursor: pointer; }
.name { color: var(--muted); font-size: 14px; }
.empty { background: var(--paper); border: 1px solid var(--line); border-radius: 8px; padding: 32px; color: var(--muted); max-width: 680px; }
h3 { font-size: 14px; text-transform: uppercase; letter-spacing: 0.5px; color: var(--k-ink); margin: 16px 0 6px; }
.counts, .busy { font-size: 14px; color: var(--muted); }
.error { color: var(--bad); }
.link { border: none; background: none; color: var(--accent); padding: 4px 0; text-decoration: underline; }
</style>
