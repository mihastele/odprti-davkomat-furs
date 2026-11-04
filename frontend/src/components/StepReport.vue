<script setup>
import { computed, ref } from 'vue'
import { ApiError, call, download } from '../api.js'
import DiagnosticsTable from './DiagnosticsTable.vue'

const props = defineProps({ ledger: { type: Object, required: true } })

const year = ref(new Date().getFullYear() - 1)
const taxNumber = ref('')
const allowIncomplete = ref(false)
const busy = ref(false)
const error = ref('')
const result = ref(null)

const taxNumberOk = computed(() => /^\d{8}$/.test(taxNumber.value))

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
    <h2 id="report-h">3. Create the declarations</h2>
    <div class="row">
      <div>
        <label for="year">Tax year</label>
        <input id="year" v-model="year" type="number" min="2000" max="2100" />
      </div>
      <div>
        <label for="tax">Your tax number (8 digits)</label>
        <input id="tax" v-model="taxNumber" type="text" inputmode="numeric" maxlength="8" autocomplete="off" />
      </div>
    </div>
    <label class="check">
      <input v-model="allowIncomplete" type="checkbox" />
      <span><strong>Also write XML when something had to be left out because of errors.</strong>
        For inspection only. Never file it.</span>
    </label>
    <div class="actions">
      <button class="primary" type="button" :disabled="busy || !taxNumberOk" @click="run">{{ busy ? 'Working...' : 'Create' }}</button>
    </div>
    <div v-if="error" class="banner error" role="alert">{{ error }}</div>

    <div v-if="result" aria-live="polite">
      <div v-if="result.complete" class="banner ok">Complete: no instrument or payment was left out.</div>
      <div v-else class="banner error" role="alert">
        INCOMPLETE: something was left out because of an error (see the findings below).
        <span v-if="result.xmlWithheld"> No XML was produced.</span>
      </div>
      <div v-if="result.draftData" class="banner warn" role="alert">
        This result contains data from a DRAFT importer, written without a real statement. Verify every figure that comes from it.
      </div>

      <h3>Result per security (cross-check)</h3>
      <p class="muted small">Realised result in EUR, rounded to cents for reading. eDavki computes the declared figures itself from the sheet rows.</p>
      <p v-if="!(result.gains ?? []).length" class="muted">No sales in this year.</p>
      <div v-else class="scroll">
        <table>
          <thead><tr><th>Instrument</th><th>Symbol</th><th class="num">Gain / loss EUR</th></tr></thead>
          <tbody>
            <tr v-for="g in result.gains" :key="g.instrument"><td><code>{{ g.instrument }}</code></td><td>{{ g.symbol }}</td><td class="num">{{ g.gainEur }}</td></tr>
          </tbody>
        </table>
      </div>

      <h3>Files</h3>
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
        <li v-if="!result.kdvpXml && !result.divXml && !result.obrXml" class="muted">No declaration files.</li>
      </ul>
      <div class="actions">
        <button v-if="result.kdvpXml" class="primary" type="button" @click="download(`kdvp-${year}.xml`, result.kdvpXml, 'application/xml')">Download Doh-KDVP XML</button>
        <button v-if="result.divXml" class="primary" type="button" @click="download(`div-${year}.xml`, result.divXml, 'application/xml')">Download Doh-Div XML</button>
        <button v-if="result.obrXml" class="primary" type="button" @click="download(`obr-${year}.xml`, result.obrXml, 'application/xml')">Download Doh-Obr XML</button>
        <button class="secondary" type="button" @click="download(`audit-${year}.md`, result.auditMarkdown, 'text/markdown')">Download audit report</button>
        <button class="secondary" type="button" @click="download(`audit-${year}.json`, result.auditJson, 'application/json')">Download audit data (JSON)</button>
      </div>

      <h3>Findings</h3>
      <DiagnosticsTable :diagnostics="result.diagnostics ?? []" />

      <h3>Audit report</h3>
      <p class="muted small">Every figure traced to the statement line it came from.</p>
      <pre>{{ result.auditMarkdown }}</pre>
    </div>
  </section>
</template>
