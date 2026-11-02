<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { call, hasToken, initToken } from './api.js'
import StepImport from './components/StepImport.vue'
import StepCheck from './components/StepCheck.vue'
import StepReport from './components/StepReport.vue'

const step = ref('import')
const info = ref(null)
const ledger = ref(null)
const fatal = ref('')

async function boot() {
  if (!initToken()) {
    if (!hasToken()) fatal.value = 'This page needs the address printed by `opendavki serve`, including the part after #. Start it again and open that address.'
    if (!hasToken()) return
  }
  try {
    info.value = await call('GET', '/api/info')
    fatal.value = ''
  } catch (e) {
    fatal.value = `Cannot reach the local server: ${e.message}`
  }
}

// Pasting the full address into a tab that already shows this page only changes the #fragment: the page does not reload.
onMounted(() => {
  boot()
  window.addEventListener('hashchange', boot)
})
onBeforeUnmount(() => window.removeEventListener('hashchange', boot))

function imported(result) {
  ledger.value = result
  step.value = 'check'
}
</script>

<template>
  <main>
    <h1>OpenDavki</h1>
    <p class="muted">Broker statements in, checked eDavki files out. Everything stays on this computer.</p>

    <!-- WIP-NOTICE:START - remove together with the README notice once the project is declared stable -->
    <div class="banner warn" role="note">
      <strong>Work in progress.</strong> Do not file anything produced here without checking it yourself. This is not tax advice.
    </div>
    <!-- WIP-NOTICE:END -->

    <div v-if="fatal" class="banner error" role="alert">{{ fatal }}</div>
    <template v-else>
      <div v-if="info?.schemas?.some((s) => s.status !== 'OK')" class="banner error" role="alert">
        The official schemas could not be verified: {{ info.schemas.filter((s) => s.status !== 'OK').map((s) => `${s.name} ${s.status}`).join(', ') }}.
      </div>
      <div v-if="info?.settings?.error" class="banner error" role="alert">Settings problem: {{ info.settings.error }}</div>

      <nav class="steps" aria-label="Steps">
        <button type="button" :aria-current="step === 'import' ? 'step' : undefined" @click="step = 'import'">1. Statements</button>
        <button type="button" :aria-current="step === 'check' ? 'step' : undefined" :disabled="!ledger" @click="step = 'check'">2. Check</button>
        <button type="button" :aria-current="step === 'report' ? 'step' : undefined" :disabled="!ledger" @click="step = 'report'">3. Declarations</button>
      </nav>

      <StepImport v-show="step === 'import'" :info="info" @imported="imported" />
      <StepCheck v-if="ledger" v-show="step === 'check'" :ledger="ledger" @next="step = 'report'" />
      <StepReport v-if="ledger" v-show="step === 'report'" :ledger="ledger" />

      <footer v-if="info">
        Exchange rates: {{ info.settings?.fxSource }} · fees in cost: {{ info.settings?.includeFees ? 'yes' : 'no' }} · time zone: {{ info.settings?.timezone }}
        · settings come from <code>.env</code> (see <code>.env.example</code>).
      </footer>
    </template>
  </main>
</template>
