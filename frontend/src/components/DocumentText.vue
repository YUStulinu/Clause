<script setup lang="ts">
import { computed, nextTick, watch } from 'vue'
import { segments, type Mark } from '../lib/segments'
import type { Clause } from '../lib/types'

// The contract, clause by clause, with the quotes of the findings marked where they are.
const props = defineProps<{
  text: string
  clauses: Clause[]
  marks: (Mark & { kind: string; verified: boolean })[]
  selected: string | null
}>()
const emit = defineEmits<{ select: [id: string] }>()

const kindOf = computed(() => new Map(props.marks.map((m) => [m.id, m])))

const blocks = computed(() =>
  props.clauses.map((c) => ({
    clause: c,
    segments: segments(props.text, props.marks, c.start, c.end),
  })),
)

function classes(ids: string[]): string[] {
  if (!ids.length) return []
  const m = kindOf.value.get(ids[ids.length - 1])!
  return ['mark', `kind-${m.kind}`, m.verified ? '' : 'unconfirmed', ids.includes(props.selected ?? '') ? 'selected' : '']
}

watch(
  () => props.selected,
  async (id) => {
    if (!id) return
    await nextTick()
    document.querySelector(`[data-mark~="${id}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  },
)
</script>

<template>
  <article class="doc">
    <section v-for="b in blocks" :key="b.clause.id" class="clause" :class="`level-${Math.min(b.clause.level, 3)}`">
      <div v-if="b.clause.number || b.clause.title" class="tag">
        {{ b.clause.number }} {{ b.clause.title }}
      </div>
      <p>
        <template v-for="s in b.segments" :key="s.start">
          <span v-if="s.marks.length" :class="classes(s.marks)" :data-mark="s.marks.join(' ')"
                @click="emit('select', s.marks[s.marks.length - 1])">{{ s.text }}</span>
          <template v-else>{{ s.text }}</template>
        </template>
      </p>
    </section>
  </article>
</template>

<style scoped>
.doc {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 24px 32px;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 15px;
  line-height: 1.65;
}
.clause { position: relative; margin-bottom: 6px; }
.clause p { margin: 0; white-space: pre-wrap; }
.tag {
  position: absolute;
  left: -30px;
  top: 2px;
  width: 0;
  overflow: visible;
  white-space: nowrap;
  font: 11px system-ui, sans-serif;
  color: var(--muted);
  opacity: 0;
}
.clause:hover .tag { opacity: 1; }
.mark {
  background: var(--k);
  border-bottom: 2px solid var(--k-ink);
  cursor: pointer;
  border-radius: 2px;
}
.mark.unconfirmed { background: none; border-bottom: 2px dashed #b9b4a6; }
.mark.selected { outline: 2px solid var(--k-ink); outline-offset: 1px; }
</style>
