<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ diagnostics: { type: Array, default: () => [] } })

const levels = ['ERROR', 'WARNING', 'INFO']
const shown = ref(new Set(['ERROR', 'WARNING']))

const counts = computed(() => Object.fromEntries(levels.map((l) => [l, props.diagnostics.filter((d) => d.severity === l).length])))
const rows = computed(() => props.diagnostics.filter((d) => shown.value.has(d.severity)))

function toggle(level) {
  const next = new Set(shown.value)
  if (next.has(level)) next.delete(level)
  else next.add(level)
  shown.value = next
}

function where(d) {
  return d.source ? `${d.source.file}:${d.source.line}` : '-'
}
</script>

<template>
  <div>
    <div class="filters" role="group" aria-label="Filter findings by severity">
      <button v-for="l in levels" :key="l" type="button" :aria-pressed="shown.has(l)" @click="toggle(l)">
        {{ l }} ({{ counts[l] }})
      </button>
    </div>
    <p v-if="diagnostics.length === 0" class="muted">No findings.</p>
    <p v-else-if="rows.length === 0" class="muted">Nothing to show with this filter.</p>
    <div v-else class="scroll">
      <table>
        <thead><tr><th>Severity</th><th>Code</th><th>Where</th><th>Message</th></tr></thead>
        <tbody>
          <tr v-for="(d, i) in rows" :key="i">
            <td><span class="badge" :class="d.severity">{{ d.severity }}</span></td>
            <td><code>{{ d.code }}</code></td>
            <td><code>{{ where(d) }}</code></td>
            <td>{{ d.message }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
