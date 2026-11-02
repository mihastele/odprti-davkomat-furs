<script setup>
import { ref } from 'vue'
import { ApiError, call, fileToBase64 } from '../api.js'

const props = defineProps({ info: { type: Object, default: null } })
const emit = defineEmits(['imported'])

const files = ref([])
const profiles = ref([])
const adapter = ref('')
const busy = ref(false)
const error = ref('')
const result = ref(null)
const layout = ref('')

function pick(event) {
  files.value = [...event.target.files]
  result.value = null
  error.value = ''
  layout.value = ''
}

function pickProfiles(event) {
  profiles.value = [...event.target.files]
}

async function runImport() {
  busy.value = true
  error.value = ''
  layout.value = ''
  try {
    const body = {
      files: await Promise.all(files.value.map(async (f) => ({ name: f.name, contentBase64: await fileToBase64(f) }))),
      adapter: adapter.value || null,
      profiles: await Promise.all(profiles.value.map(async (f) => ({ name: f.name, text: await f.text() })))
    }
    result.value = await call('POST', '/api/import', body)
    emit('imported', result.value)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Could not reach the local server: ${e.message}`
  } finally {
    busy.value = false
  }
}

async function openLedger(event) {
  const file = event.target.files[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    result.value = { ...(await call('POST', '/api/ledger', { text: await file.text() })), files: [], hasErrors: false }
    emit('imported', result.value)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

/** Shows only the layout of the first chosen file (column names and value shapes), safe to copy into an issue. */
async function describeFirst() {
  if (!files.value.length) return
  busy.value = true
  try {
    const f = files.value[0]
    layout.value = (await call('POST', '/api/describe', { name: f.name, contentBase64: await fileToBase64(f), keep: [] })).text
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function period(f) {
  return f.first ? `${f.first} to ${f.last}` : '-'
}
</script>

<template>
  <section class="card" aria-labelledby="import-h">
    <h2 id="import-h">1. Choose your broker statements</h2>
    <p class="muted">
      Files are read by the program on this computer. Nothing is uploaded anywhere. Import <strong>all</strong> statements from your first
      purchase onwards, otherwise sales cannot be matched to purchases.
    </p>

    <label for="files">Statement files (CSV)</label>
    <input id="files" type="file" multiple @change="pick" />

    <div class="row">
      <div>
        <label for="adapter">Broker format</label>
        <select id="adapter" v-model="adapter">
          <option value="">Detect automatically</option>
          <option v-for="a in info?.adapters ?? []" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </div>
      <div>
        <label for="profiles">Extra broker profiles (JSON, optional)</label>
        <input id="profiles" type="file" accept=".json,application/json" multiple @change="pickProfiles" />
      </div>
    </div>

    <div class="actions">
      <button class="primary" type="button" :disabled="busy || !files.length" @click="runImport">
        {{ busy ? 'Working...' : 'Import' }}
      </button>
      <label class="secondary" style="font-weight: 400; margin: 0; cursor: pointer">
        <span class="secondary" style="display: inline-block; padding: 7px 14px; border: 1px solid var(--accent); border-radius: 8px; color: var(--accent)">Open a saved ledger</span>
        <input type="file" accept=".json,application/json" style="display: none" @change="openLedger" />
      </label>
    </div>

    <div v-if="error" class="banner error" role="alert">
      {{ error }}
      <div v-if="files.length" class="actions">
        <button class="secondary" type="button" :disabled="busy" @click="describeFirst">Show the layout of the first file</button>
      </div>
    </div>

    <div v-if="layout">
      <h3>Layout of the first file</h3>
      <p class="muted small">Column names and value shapes only. Read it before sharing it; it is meant for adding support for your broker.</p>
      <pre>{{ layout }}</pre>
    </div>

    <div v-if="result && result.files.length" class="scroll">
      <h3>Result</h3>
      <table>
        <thead><tr><th>File</th><th>Format</th><th class="num">Rows</th><th class="num">Mapped</th><th class="num">Skipped</th><th class="num">Failed</th><th>Period</th></tr></thead>
        <tbody>
          <tr v-for="f in result.files" :key="f.file">
            <td>{{ f.file }}</td>
            <td><code>{{ f.adapter }}</code></td>
            <td class="num">{{ f.rowsRead }}</td>
            <td class="num">{{ f.rowsMapped }}</td>
            <td class="num">{{ f.rowsSkipped }}</td>
            <td class="num">{{ f.rowsFailed }}</td>
            <td>{{ period(f) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
