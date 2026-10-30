<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api'
import type { Comparison, Contract, Entry } from '../lib/types'

const props = defineProps<{ contracts: Contract[] }>()
const emit = defineEmits<{ uploaded: [c: Contract] }>()

const a = ref('')
const b = ref('')
const result = ref<Comparison | null>(null)
const busy = ref(false)
const error = ref('')
const showUnchanged = ref(false)

async function upload(e: Event, slot: 'a' | 'b') {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    const c = await api.upload(file)
    emit('uploaded', c)
    if (slot === 'a') a.value = c.id
    else b.value = c.id
  } catch (err) {
    error.value = (err as Error).message
  }
}

// Two contracts already in this session: ?a=<id>&b=<id> compares them at once.
onMounted(async () => {
  const q = new URLSearchParams(location.search)
  if (q.get('a') && q.get('b')) {
    a.value = q.get('a')!
    b.value = q.get('b')!
    await compare()
  }
})

async function compare() {
  busy.value = true
  error.value = ''
  try {
    result.value = await api.compare(a.value, b.value)
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    busy.value = false
  }
}

const shown = computed(() =>
  (result.value?.entries ?? []).filter((e) => showUnchanged.value || (e.kind !== 'SAME' && !(e.kind === 'COSMETIC' && !e.moved))),
)

function label(e: Entry): string {
  if (e.kind === 'ADDED') return 'Added'
  if (e.kind === 'REMOVED') return 'Removed'
  if (e.kind === 'MODIFIED') return e.meaning ? (e.meaning.meaning === 'changed' ? 'Meaning changed' : 'Reworded, same meaning') : 'Changed'
  if (e.kind === 'COSMETIC') return e.moved ? 'Moved, same words' : 'Only formatting'
  return 'Unchanged'
}

function tone(e: Entry): string {
  if (e.kind === 'ADDED' || e.kind === 'REMOVED') return 'major'
  if (e.kind === 'MODIFIED') return e.meaning?.meaning === 'same' ? 'minor' : 'major'
  return 'minor'
}

function clauseText(side: 'a' | 'b', e: Entry): string {
  const c = side === 'a' ? e.a : e.b
  const t = side === 'a' ? result.value!.a.text : result.value!.b.text
  return c ? t.slice(c.start, c.end).trim() : ''
}

function heading(e: Entry): string {
  const c = e.b ?? e.a
  return c ? `${c.number} ${c.title}`.trim() || 'Clause' : ''
}
</script>

<template>
  <div class="compare">
    <div class="pick">
      <div v-for="slot in (['a', 'b'] as const)" :key="slot" class="slot">
        <div class="title">{{ slot === 'a' ? 'Earlier version' : 'Later version' }}</div>
        <select v-if="props.contracts.length" :value="slot === 'a' ? a : b" @change="(e) => (slot === 'a' ? (a = (e.target as HTMLSelectElement).value) : (b = (e.target as HTMLSelectElement).value))">
          <option value="">choose…</option>
          <option v-for="c in props.contracts" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <label class="upload"><input type="file" accept=".pdf,.docx,.txt" @change="(e) => upload(e, slot)" /><span>upload…</span></label>
      </div>
      <button class="primary" :disabled="!a || !b || a === b || busy" @click="compare">{{ busy ? 'Comparing…' : 'Compare' }}</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="result?.note" class="error">{{ result.note }}</p>

    <template v-if="result">
      <p class="counts">
        {{ result.counts.modified }} clauses changed, {{ result.counts.added }} added, {{ result.counts.removed }} removed,
        {{ result.counts.same + result.counts.cosmetic }} unchanged or only reformatted.
        <button class="link" @click="showUnchanged = !showUnchanged">{{ showUnchanged ? 'Hide' : 'Show' }} unchanged</button>
      </p>
      <div v-for="e in shown" :key="e.id" class="entry" :class="tone(e)">
        <div class="head">
          <span class="badge">{{ label(e) }}</span>
          <span v-if="e.moved" class="badge moved">moved</span>
          <strong>{{ heading(e) }}</strong>
        </div>
        <div v-if="e.facts.length || e.negationChanged || e.modalityChanged" class="facts">
          <span v-if="e.negationChanged" class="fact">a "not" came or went</span>
          <span v-if="e.modalityChanged" class="fact">an obligation became a permission, or the reverse</span>
          <span v-for="(f, i) in e.facts" :key="i" class="fact">
            <del v-if="f.before">{{ f.before }}</del>
            <template v-if="f.before && f.after"> → </template>
            <ins v-if="f.after">{{ f.after }}</ins>
          </span>
        </div>
        <p v-if="e.meaning?.explanation" class="explanation">{{ e.meaning.explanation }}</p>
        <p v-else-if="e.meaning?.reasons?.length" class="reasons">{{ e.meaning.reasons.join('; ') }}</p>
        <p v-if="e.diff.length" class="diff">
          <template v-for="(r, i) in e.diff" :key="i">
            <del v-if="r.kind === 'DELETED'">{{ r.text }}</del>
            <ins v-else-if="r.kind === 'INSERTED'">{{ r.text }}</ins>
            <template v-else>{{ r.text }}</template>
          </template>
        </p>
        <p v-else-if="e.kind === 'ADDED'" class="diff"><ins>{{ clauseText('b', e) }}</ins></p>
        <p v-else-if="e.kind === 'REMOVED'" class="diff"><del>{{ clauseText('a', e) }}</del></p>
        <p v-else-if="showUnchanged" class="diff">{{ clauseText('b', e) }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pick { display: flex; gap: 16px; align-items: flex-end; margin-bottom: 16px; flex-wrap: wrap; }
.slot { background: var(--paper); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; display: flex; gap: 8px; align-items: center; }
.title { font-weight: 600; margin-right: 6px; }
.upload input { display: none; }
.upload span { color: var(--accent); cursor: pointer; text-decoration: underline; }
.counts { color: var(--muted); }
.entry { background: var(--paper); border: 1px solid var(--line); border-left: 4px solid #c9c4b6; border-radius: 6px; padding: 10px 14px; margin-bottom: 10px; }
.entry.major { border-left-color: var(--bad); }
.head { display: flex; gap: 8px; align-items: center; }
.badge { font-size: 12px; padding: 1px 8px; border-radius: 10px; background: #efede6; color: var(--ink); }
.major .badge:first-child { background: #fdecea; color: var(--bad); }
.badge.moved { background: #eef3f9; color: var(--accent); }
.facts { margin: 6px 0; display: flex; gap: 8px; flex-wrap: wrap; }
.fact { font-size: 13px; background: #f7f3e8; padding: 2px 8px; border-radius: 4px; }
.explanation { margin: 6px 0; }
.reasons { margin: 6px 0; color: var(--muted); font-size: 14px; }
.diff { font-family: Georgia, serif; font-size: 14px; white-space: pre-wrap; margin: 6px 0 0; color: #333; }
del { background: #fbe1df; color: #8c1d16; text-decoration: line-through; }
ins { background: #ddf3e4; color: #1e5c34; text-decoration: none; }
.error { color: var(--bad); }
.link { border: none; background: none; color: var(--accent); text-decoration: underline; padding: 0 4px; }
</style>
