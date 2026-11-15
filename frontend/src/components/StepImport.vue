<script setup>
import { onMounted, ref } from 'vue'
import { ApiError, base64ToText, call, desktopAvailable, fileToBase64, pickDesktopFiles } from '../api.js'
import { t } from '../i18n.js'

const props = defineProps({ info: { type: Object, default: null } })
const emit = defineEmits(['imported'])

const files = ref([])
const profiles = ref([])
const adapter = ref('')
const busy = ref(false)
const error = ref('')
const result = ref(null)
const layout = ref('')
const input = ref(null)
const desktop = ref(desktopAvailable())

onMounted(() => {
  // The JavaFX bridge is installed after the page load, so re-check shortly after mount.
  const refresh = () => { if (desktopAvailable()) desktop.value = true }
  refresh()
  setTimeout(refresh, 500)
  setTimeout(refresh, 1500)
})

function chooseFiles() {
  if (desktopAvailable()) {
    files.value = pickDesktopFiles(true) ?? []
    result.value = null
    error.value = ''
    layout.value = ''
  } else {
    input.value?.click()
  }
}

function pick(event) {
  files.value = [...event.target.files]
  result.value = null
  error.value = ''
  layout.value = ''
}

function drop(event) {
  files.value = [...(event.dataTransfer?.files ?? [])]
  result.value = null
  error.value = ''
  layout.value = ''
}

function pickProfiles(event) {
  profiles.value = [...event.target.files]
}

function chooseProfiles() {
  if (desktopAvailable()) profiles.value = pickDesktopFiles(true) ?? []
}

async function runImport() {
  if (!files.value.length) {
    error.value = t('import.noFiles')
    return
  }
  busy.value = true
  error.value = ''
  layout.value = ''
  try {
    const body = {
      files: await Promise.all(files.value.map(async (f) => ({ name: f.name, contentBase64: f.base64 ?? await fileToBase64(f) }))),
      adapter: adapter.value || null,
      profiles: await Promise.all(profiles.value.map(async (f) => ({
        name: f.name,
        text: f.text ?? (f.base64 ? base64ToText(f.base64) : await f.text())
      })))
    }
    result.value = await call('POST', '/api/import', body)
    emit('imported', result.value)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}`
  } finally {
    busy.value = false
  }
}

async function openLedgerText(text) {
  result.value = { ...(await call('POST', '/api/ledger', { text })), files: [], hasErrors: false }
  emit('imported', result.value)
}

async function openProjectText(text) {
  const opened = await call('POST', '/api/project/open', { text })
  result.value = { ...opened, files: [], hasErrors: (opened.diagnostics ?? []).some((d) => d.severity === 'ERROR') }
  emit('imported', result.value)
}

async function openLedger(event) {
  const file = event.target.files[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    await openLedgerText(await file.text())
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
    event.target.value = ''
  }
}

async function openProject(event) {
  const file = event.target.files[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    await openProjectText(await file.text())
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}`
  } finally {
    busy.value = false
    event.target.value = ''
  }
}

async function openLedgerDesktop() {
  const picked = pickDesktopFiles(false)?.[0]
  if (!picked) return
  busy.value = true
  error.value = ''
  try { await openLedgerText(base64ToText(picked.base64)) }
  catch (e) { error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}` }
  finally { busy.value = false }
}

async function openProjectDesktop() {
  const picked = pickDesktopFiles(false)?.[0]
  if (!picked) return
  busy.value = true
  error.value = ''
  try { await openProjectText(base64ToText(picked.base64)) }
  catch (e) { error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}` }
  finally { busy.value = false }
}

/** Shows only the layout of the first chosen file (column names and value shapes), safe to copy into an issue. */
async function describeFirst() {
  if (!files.value.length) return
  busy.value = true
  try {
    const f = files.value[0]
    layout.value = (await call('POST', '/api/describe', { name: f.name, contentBase64: f.base64 ?? await fileToBase64(f), keep: [] })).text
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
    <div class="welcome">
      <h2>{{ t('import.welcome') }}</h2>
      <p class="muted">{{ t('import.welcomeText') }}</p>
      <ol class="muted small">
        <li>{{ t('import.one') }}</li>
        <li>{{ t('import.two') }}</li>
        <li>{{ t('import.three') }}</li>
      </ol>
    </div>

    <h2 id="import-h">{{ t('import.heading') }}</h2>
    <p class="muted">{{ t('import.privacy') }}</p>

    <div class="dropzone" role="button" tabindex="0" @click="chooseFiles" @keydown.enter.prevent="chooseFiles"
         @keydown.space.prevent="chooseFiles" @dragover.prevent @drop.prevent="drop">
      <strong>{{ t('import.drop') }}</strong>
      <span class="muted">{{ t('import.browse') }}</span>
      <input id="files" ref="input" type="file" multiple accept=".csv,.pdf,.xlsx,text/csv" @change="pick" />
    </div>
    <ul v-if="files.length" class="file-list small">
      <li v-for="f in files" :key="f.name"><code>{{ f.name }}</code> <span class="muted">{{ Math.ceil((f.size ?? 0) / 1024) }} KB</span></li>
    </ul>

    <details class="advanced">
      <summary>{{ t('import.advanced') }}</summary>
      <div class="row">
        <div>
          <label for="adapter">{{ t('import.adapter') }}</label>
          <select id="adapter" v-model="adapter">
            <option value="">{{ t('import.detect') }}</option>
            <option v-for="a in info?.adapters ?? []" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </div>
        <div>
          <label for="profiles">{{ t('import.profiles') }}</label>
          <input id="profiles" type="file" accept=".json,application/json" multiple @change="pickProfiles" />
          <button v-if="desktop" class="secondary" type="button" @click="chooseProfiles">{{ t('settings.upload') }}</button>
        </div>
      </div>
    </details>

    <div class="actions">
      <button class="primary" type="button" :disabled="busy || !files.length" @click="runImport">
        {{ busy ? t('import.working') : t('import.run') }}
      </button>
      <button v-if="desktop" class="secondary" type="button" @click="openProjectDesktop">{{ t('import.openProject') }}</button>
      <label v-else class="secondary file-button">
        {{ t('import.openProject') }}
        <input id="project-file" type="file" accept=".json,application/json" @change="openProject" />
      </label>
      <button v-if="desktop" class="secondary" type="button" @click="openLedgerDesktop">{{ t('import.openLedger') }}</button>
      <label v-else class="secondary file-button">
        {{ t('import.openLedger') }}
        <input type="file" accept=".json,application/json" @change="openLedger" />
      </label>
    </div>

    <div v-if="error" class="banner error" role="alert">
      {{ error }}
      <div v-if="files.length" class="actions">
        <button class="secondary" type="button" :disabled="busy" @click="describeFirst">{{ t('import.layout') }}</button>
      </div>
    </div>

    <div v-if="layout">
      <h3>{{ t('import.layout') }}</h3>
      <p class="muted small">Column names and value shapes only. Read it before sharing it; it is meant for adding support for your broker.</p>
      <pre>{{ layout }}</pre>
    </div>

    <div v-if="result?.files?.length" class="scroll">
      <h3>{{ t('import.result') }}</h3>
      <table>
        <thead><tr><th>{{ t('import.file') }}</th><th>{{ t('import.format') }}</th><th class="num">{{ t('import.rows') }}</th><th class="num">{{ t('import.mapped') }}</th><th class="num">{{ t('import.skipped') }}</th><th class="num">{{ t('import.failed') }}</th><th>{{ t('import.period') }}</th></tr></thead>
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
