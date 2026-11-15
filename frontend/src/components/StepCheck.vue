<script setup>
import { computed, ref } from 'vue'
import { download, fetchText } from '../api.js'
import { t } from '../i18n.js'
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
    <h2 id="check-h">{{ t('check.heading') }}</h2>
    <div v-if="errors" class="banner error" role="alert">{{ t('check.errors', { count: errors }) }}</div>
    <div v-else class="banner ok">{{ t('check.ok') }}</div>

    <h3>{{ t('check.findings') }}</h3>
    <DiagnosticsTable :diagnostics="ledger.diagnostics ?? []" />

    <h3>{{ t('check.entries', { count: ledger.entries.length }) }}</h3>
    <label for="q">{{ t('check.filter') }}</label>
    <input id="q" v-model="filter" type="text" :placeholder="t('check.filterPlaceholder')" />
    <div class="scroll">
      <table>
        <thead><tr><th>{{ t('check.date') }}</th><th>{{ t('check.broker') }}</th><th>{{ t('check.instrument') }}</th><th>{{ t('check.what') }}</th><th>{{ t('check.source') }}</th></tr></thead>
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
      {{ t('check.showing', { shown: LIMIT, total: matching }) }}
      <button class="secondary" type="button" @click="showAll = true">{{ t('check.showAll') }}</button>
    </p>

    <div class="actions">
      <button class="primary" type="button" @click="emit('next')">{{ t('check.continue') }}</button>
      <button class="secondary" type="button" @click="saveLedger">{{ t('check.saveLedger') }}</button>
    </div>
  </section>
</template>
