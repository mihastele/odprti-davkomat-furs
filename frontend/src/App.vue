<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { call, forgetLedger, hasToken, initToken, rememberLedger, rememberedLedger } from './api.js'
import { availableLocales, locale, setLocale, t } from './i18n.js'
import StepImport from './components/StepImport.vue'
import StepCheck from './components/StepCheck.vue'
import StepReport from './components/StepReport.vue'
import Settings from './components/Settings.vue'

const step = ref('import')
const info = ref(null)
const ledger = ref(null)
const projectReport = ref(null)
const showSettings = ref(false)
const fatal = ref('')

async function restoreLedger() {
  const id = rememberedLedger()
  if (!id) return
  try {
    ledger.value = await call('GET', `/api/ledger/${encodeURIComponent(id)}`)
    step.value = 'check'
  } catch {
    // The server keeps ledgers in memory only; a restart makes the remembered id useless.
    forgetLedger()
  }
}

async function refreshInfo() {
  info.value = await call('GET', '/api/info')
}

async function boot() {
  if (!initToken()) {
    if (!hasToken()) fatal.value = 'This page needs the address printed by `opendavki serve`, including the part after #. Start it again and open that address.'
    if (!hasToken()) return
  }
  try {
    await refreshInfo()
    fatal.value = ''
    if (!ledger.value) await restoreLedger()
  } catch (e) {
    fatal.value = `Cannot reach the local server: ${e.message}`
  }
}

// Pasting the full address into a tab that already shows this page only changes the #fragment: the page does not reload.
onMounted(() => {
  try { document.documentElement.lang = locale.value } catch { /* not in a browser */ }
  boot()
  window.addEventListener('hashchange', boot)
})
onBeforeUnmount(() => window.removeEventListener('hashchange', boot))

function imported(result) {
  ledger.value = result
  projectReport.value = result.report ?? null
  rememberLedger(result.ledgerId)
  step.value = 'check'
}
</script>

<template>
  <main>
    <div class="topbar">
      <div>
        <h1>{{ t('app.title') }}</h1>
        <p class="muted">{{ t('app.subtitle') }}</p>
      </div>
      <div class="top-actions">
        <label class="sr-only" for="locale">{{ t('app.language') }}</label>
        <select id="locale" :value="locale" @change="setLocale($event.target.value)">
          <option v-for="l in availableLocales" :key="l.id" :value="l.id">{{ l.label }}</option>
        </select>
        <button class="secondary" type="button" :aria-expanded="showSettings" @click="showSettings = !showSettings">
          {{ t('app.settings') }}
        </button>
      </div>
    </div>

    <!-- WIP-NOTICE:START - remove together with the README notice once the project is declared stable -->
    <div class="banner warn" role="note">
      <strong>{{ t('app.wip') }}</strong>
    </div>
    <!-- WIP-NOTICE:END -->

    <div v-if="fatal" class="banner error" role="alert">{{ fatal }}</div>
    <template v-else>
      <div v-if="info?.schemas?.some((s) => s.status !== 'OK')" class="banner error" role="alert">
        {{ t('app.schemaProblem') }}: {{ info.schemas.filter((s) => s.status !== 'OK').map((s) => `${s.name} ${s.status}`).join(', ') }}.
      </div>
      <div v-if="info?.settings?.error" class="banner error" role="alert">{{ t('app.settingsProblem') }}: {{ info.settings.error }}</div>

      <Settings v-if="showSettings" @saved="refreshInfo" @close="showSettings = false" />

      <nav class="steps" aria-label="Steps">
        <button type="button" :aria-current="step === 'import' ? 'step' : undefined" @click="step = 'import'">{{ t('app.step.statements') }}</button>
        <button type="button" :aria-current="step === 'check' ? 'step' : undefined" :disabled="!ledger" @click="step = 'check'">{{ t('app.step.check') }}</button>
        <button type="button" :aria-current="step === 'report' ? 'step' : undefined" :disabled="!ledger" @click="step = 'report'">{{ t('app.step.report') }}</button>
      </nav>

      <StepImport v-show="step === 'import'" :info="info" @imported="imported" />
      <StepCheck v-if="ledger" v-show="step === 'check'" :ledger="ledger" @next="step = 'report'" />
      <StepReport v-if="ledger" v-show="step === 'report'" :ledger="ledger" :initial-report="projectReport" />

      <footer v-if="info">
        {{ t('app.footer', { fx: info.settings?.fxSource, fees: info.settings?.includeFees ? 'yes' : 'no', tz: info.settings?.timezone }) }}
        · settings: <code :title="info.paths?.settings">{{ info.paths?.settings }}</code>
        · schemas: <code :title="info.paths?.schemas">{{ info.paths?.schemas }}</code>
      </footer>
    </template>
  </main>
</template>
