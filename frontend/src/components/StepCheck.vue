<script setup>
import { computed, ref } from 'vue'
import { download, fetchText } from '../api.js'
import DiagnosticsTable from './DiagnosticsTable.vue'

const props = defineProps({ ledger: { type: Object, required: true } })
const emit = defineEmits(['next'])

const filter = ref('')
const showAll = ref(false)
const LIMIT = 200

const rows = computed(() => {
  const q = filter.value.trim().toLowerCase()
  const all = q
    ? props.ledger.entries.filter((e) => `${e.instrument} ${e.name} ${e.what} ${e.broker} ${e.date}`.toLowerCase().includes(q))
    : props.ledger.entries
  return showAll.value ? all : all.slice(0, LIMIT)
})
const matching = computed(() => {
  const q = filter.value.trim().toLowerCase()
  return q ? props.ledger.entries.filter((e) => `${e.instrument} ${e.name} ${e.what} ${e.broker} ${e.date}`.toLowerCase().includes(q)).length : props.ledger.entries.length
})
const errors = computed(() => (props.ledger.diagnostics ?? []).filter((d) => d.severity === 'ERROR').length)

async function saveLedger() {
  download('ledger.json', await fetchText(`/api/ledger/${props.ledger.ledgerId}/download`), 'application/json')
}
</script>

<template>
  <section class="card" aria-labelledby="check-h">
    <h2 id="check-h">2. Check what was read</h2>
    <div v-if="errors" class="banner error" role="alert">
      {{ errors }} error(s) found. The most common cause is a missing earlier statement (MISSING_PURCHASE_HISTORY). The report refuses to produce XML for
      anything affected.
    </div>
    <div v-else class="banner ok">No errors in the imported data. Read the warnings before continuing.</div>

    <h3>Findings</h3>
    <DiagnosticsTable :diagnostics="ledger.diagnostics ?? []" />

    <h3>Ledger entries ({{ ledger.entries.length }})</h3>
    <label for="q">Filter</label>
    <input id="q" v-model="filter" type="text" placeholder="ISIN, symbol, text, date..." />
    <div class="scroll">
      <table>
        <thead><tr><th>Date</th><th>Broker</th><th>Instrument</th><th>What</th><th>Source</th></tr></thead>
        <tbody>
          <tr v-for="(e, i) in rows" :key="i">
            <td>{{ e.date }}</td>
            <td>{{ e.broker }}</td>
            <td><code>{{ e.instrument }}</code> <span class="muted">{{ e.name }}</span></td>
            <td>{{ e.what }}</td>
            <td><code>{{ e.source }}</code></td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!showAll && matching > LIMIT" class="muted">
      Showing the first {{ LIMIT }} of {{ matching }}.
      <button class="secondary" type="button" @click="showAll = true">Show all</button>
    </p>

    <div class="actions">
      <button class="primary" type="button" @click="emit('next')">Continue to the report</button>
      <button class="secondary" type="button" @click="saveLedger">Save the ledger (JSON)</button>
    </div>
  </section>
</template>
