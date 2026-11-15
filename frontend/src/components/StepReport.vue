<script setup>
import { computed, ref, watch } from 'vue'
import { ApiError, call, download } from '../api.js'
import { t } from '../i18n.js'
import DiagnosticsTable from './DiagnosticsTable.vue'

const props = defineProps({ ledger: { type: Object, required: true }, initialReport: { type: Object, default: null } })

const year = ref(props.initialReport?.year ?? new Date().getFullYear() - 1)
const taxNumber = ref(props.initialReport?.taxNumber ?? '')
const allowIncomplete = ref(false)
const includeTaxNumber = ref(false)
const busy = ref(false)
const savingProject = ref(false)
const error = ref('')
const projectMessage = ref('')
const result = ref(null)

const taxNumberOk = computed(() => /^\d{8}$/.test(taxNumber.value))

watch(() => props.initialReport, (report) => {
  if (report?.year) year.value = report.year
  if (report?.taxNumber) taxNumber.value = report.taxNumber
})

async function saveProject() {
  savingProject.value = true
  error.value = ''
  projectMessage.value = ''
  try {
    const saved = await call('POST', '/api/project/save', {
      ledgerId: props.ledger.ledgerId,
      year: Number(year.value),
      taxNumber: taxNumber.value || null,
      includeTaxNumber: includeTaxNumber.value
    })
    download(saved.filename, saved.text, 'application/json')
    projectMessage.value = t('report.projectSaved')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}`
  } finally {
    savingProject.value = false
  }
}

async function run() {
  busy.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await call('POST', '/api/report', {
      ledgerId: props.ledger.ledgerId, year: Number(year.value), taxNumber: taxNumber.value, allowIncomplete: allowIncomplete.value
    })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Could not reach the local server: ${e.message}`
  } finally {
    busy.value = false
  }
}

function badge(v) {
  if (!v) return null
  if (!v.schemaAvailable) return { cls: 'WARNING', text: 'schema not found' }
  return v.valid ? { cls: 'OK', text: 'valid against the official schema' } : { cls: 'ERROR', text: 'INVALID against the official schema' }
}
</script>

<template>
  <section class="card" aria-labelledby="report-h">
    <h2 id="report-h">{{ t('report.heading') }}</h2>
    <div class="row">
      <div>
        <label for="year">{{ t('report.year') }}</label>
        <input id="year" v-model="year" type="number" min="2000" max="2100" />
      </div>
      <div>
        <label for="tax">{{ t('report.taxNumber') }}</label>
        <input id="tax" v-model="taxNumber" type="text" inputmode="numeric" maxlength="8" autocomplete="off" />
      </div>
    </div>
    <label class="check">
      <input v-model="allowIncomplete" type="checkbox" />
      <span><strong>{{ t('report.allowIncomplete') }}</strong>
        {{ t('report.allowIncompleteNote') }}</span>
    </label>
    <div class="actions">
      <button class="primary" type="button" :disabled="busy || !taxNumberOk" @click="run">{{ busy ? t('report.working') : t('report.create') }}</button>
      <button class="secondary" type="button" :disabled="savingProject" @click="saveProject">{{ t('report.saveProject') }}</button>
    </div>
    <label class="check">
      <input id="include-tax" v-model="includeTaxNumber" type="checkbox" />
      <span>{{ t('report.includeTaxNumber') }}</span>
    </label>
    <div v-if="error" class="banner error" role="alert">{{ error }}</div>
    <div v-if="projectMessage" class="banner ok" role="status">{{ projectMessage }}</div>

    <div v-if="result" aria-live="polite">
      <div v-if="result.complete" class="banner ok">{{ t('report.complete') }}</div>
      <div v-else class="banner error" role="alert">
        {{ t('report.incomplete') }}
        <span v-if="result.xmlWithheld"> {{ t('report.xmlWithheld') }}</span>
      </div>
      <div v-if="result.draftData" class="banner warn" role="alert">
        {{ t('report.draft') }}
      </div>

      <h3>{{ t('report.gains') }}</h3>
      <p class="muted small">{{ t('report.gainsNote') }}</p>
      <p v-if="!(result.gains ?? []).length" class="muted">{{ t('report.noSales') }}</p>
      <div v-else class="scroll">
        <table>
          <thead><tr><th>{{ t('report.instrument') }}</th><th>{{ t('report.symbol') }}</th><th class="num">{{ t('report.gain') }}</th></tr></thead>
          <tbody>
            <tr v-for="g in result.gains" :key="g.instrument"><td><code>{{ g.instrument }}</code></td><td>{{ g.symbol }}</td><td class="num">{{ g.gainEur }}</td></tr>
          </tbody>
        </table>
      </div>

      <h3>{{ t('report.files') }}</h3>
      <ul>
        <li v-if="result.kdvpXml">
          Doh-KDVP <span v-if="badge(result.kdvpValidation)" class="badge" :class="badge(result.kdvpValidation).cls">{{ badge(result.kdvpValidation).text }}</span>
          <div v-for="(i, n) in result.kdvpValidation?.issues ?? []" :key="n" class="small"><code>{{ i }}</code></div>
        </li>
        <li v-if="result.divXml">
          Doh-Div <span v-if="badge(result.divValidation)" class="badge" :class="badge(result.divValidation).cls">{{ badge(result.divValidation).text }}</span>
          <div v-for="(i, n) in result.divValidation?.issues ?? []" :key="n" class="small"><code>{{ i }}</code></div>
        </li>
        <li v-if="result.obrXml">
          Doh-Obr (interest) <span v-if="badge(result.obrValidation)" class="badge" :class="badge(result.obrValidation).cls">{{ badge(result.obrValidation).text }}</span>
          <div v-for="(i, n) in result.obrValidation?.issues ?? []" :key="n" class="small"><code>{{ i }}</code></div>
        </li>
        <li v-if="!result.kdvpXml && !result.divXml && !result.obrXml" class="muted">{{ t('report.noFiles') }}</li>
      </ul>
      <div class="actions">
        <button v-if="result.kdvpXml" class="primary" type="button" @click="download(`kdvp-${year}.xml`, result.kdvpXml, 'application/xml')">{{ t('report.downloadKdvp') }}</button>
        <button v-if="result.divXml" class="primary" type="button" @click="download(`div-${year}.xml`, result.divXml, 'application/xml')">{{ t('report.downloadDiv') }}</button>
        <button v-if="result.obrXml" class="primary" type="button" @click="download(`obr-${year}.xml`, result.obrXml, 'application/xml')">{{ t('report.downloadObr') }}</button>
        <button class="secondary" type="button" @click="download(`audit-${year}.md`, result.auditMarkdown, 'text/markdown')">{{ t('report.downloadAudit') }}</button>
        <button class="secondary" type="button" @click="download(`audit-${year}.json`, result.auditJson, 'application/json')">{{ t('report.downloadAuditJson') }}</button>
      </div>

      <h3>{{ t('report.findings') }}</h3>
      <DiagnosticsTable :diagnostics="result.diagnostics ?? []" />

      <h3>{{ t('report.audit') }}</h3>
      <p class="muted small">{{ t('report.auditNote') }}</p>
      <pre>{{ result.auditMarkdown }}</pre>
    </div>
  </section>
</template>
