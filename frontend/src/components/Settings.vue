<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ApiError, call, desktopAvailable, fileToBase64, pickDesktopFiles } from '../api.js'
import { t } from '../i18n.js'

const emit = defineEmits(['saved', 'close'])
const state = ref(null)
const form = reactive({})
const busy = ref(false)
const message = ref('')
const error = ref('')
const desktop = ref(desktopAvailable())

async function load() {
  busy.value = true
  error.value = ''
  try {
    state.value = await call('GET', '/api/settings')
    for (const key of state.value.keys ?? []) form[key] = state.value.values?.[key] ?? ''
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}`
  } finally {
    busy.value = false
  }
}

async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    state.value = await call('PUT', '/api/settings', { values: { ...form } })
    message.value = t('settings.saved')
    emit('saved')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}`
  } finally {
    busy.value = false
  }
}

async function sendUpload(kind, key, name, base64) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const saved = await call('POST', '/api/settings/files', { kind, name, contentBase64: base64 })
    if (key) form[key] = saved.path
    state.value = await call('GET', '/api/settings')
    message.value = t('settings.uploadSaved')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : `Cannot reach the local server: ${e.message}`
  } finally {
    busy.value = false
  }
}

async function upload(event, kind, key) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    await sendUpload(kind, key, file.name, await fileToBase64(file))
  } finally {
    event.target.value = ''
  }
}

function chooseUpload(kind, key) {
  const picked = pickDesktopFiles(false)?.[0]
  if (picked) sendUpload(kind, key, picked.name, picked.base64)
}

onMounted(() => {
  load()
  const refresh = () => { if (desktopAvailable()) desktop.value = true }
  refresh()
  setTimeout(refresh, 500)
  setTimeout(refresh, 1500)
})
</script>

<template>
  <section class="card settings" aria-labelledby="settings-h">
    <div class="settings-head">
      <h2 id="settings-h">{{ t('settings.heading') }}</h2>
      <button class="secondary" type="button" @click="emit('close')">{{ t('settings.close') }}</button>
    </div>
    <p v-if="state" class="muted small">
      {{ t('settings.path') }}: <code>{{ state.path }}</code>
      <span v-if="!state.exists"> — {{ t('settings.notCreated') }}</span>
    </p>
    <div v-if="state?.overridden?.length" class="banner warn">
      {{ t('settings.overridden', { keys: state.overridden.join(', ') }) }}
    </div>
    <div v-if="error" class="banner error" role="alert">{{ error }}</div>
    <div v-if="message" class="banner ok" role="status">{{ message }}</div>

    <form v-if="state" @submit.prevent="save">
      <h3>{{ t('settings.fx') }}</h3>
      <div class="row">
        <div>
          <label for="fx-source">{{ t('settings.fxSource') }}</label>
          <select id="fx-source" v-model="form.OPENDAVKI_FX_SOURCE">
            <option value="ECB">ECB</option>
            <option value="BSI">Bank of Slovenia</option>
            <option value="FILE">CSV</option>
          </select>
        </div>
        <div>
          <label for="fx-missing">{{ t('settings.missingDay') }}</label>
          <select id="fx-missing" v-model="form.OPENDAVKI_FX_MISSING_DAY">
            <option value="PREVIOUS">Previous published rate</option>
            <option value="FAIL">Fail</option>
          </select>
        </div>
        <div>
          <label for="fx-lookback">{{ t('settings.lookback') }}</label>
          <input id="fx-lookback" v-model="form.OPENDAVKI_FX_MAX_LOOKBACK_DAYS" type="number" min="0" max="60" />
        </div>
      </div>
      <div class="row">
        <div>
          <label for="fx-ecb">{{ t('settings.ecbUrl') }}</label>
          <input id="fx-ecb" v-model="form.OPENDAVKI_FX_ECB_URL" type="url" />
        </div>
        <div>
          <label for="fx-bsi">{{ t('settings.bsiUrl') }}</label>
          <input id="fx-bsi" v-model="form.OPENDAVKI_FX_BSI_URL" type="url" />
        </div>
      </div>
      <label for="fx-file">{{ t('settings.fxFile') }}</label>
      <div class="file-row">
        <input id="fx-file" v-model="form.OPENDAVKI_FX_FILE" type="text" />
        <button v-if="desktop" class="secondary" type="button" @click="chooseUpload('fx', 'OPENDAVKI_FX_FILE')">{{ t('settings.upload') }}</button>
        <label v-else class="secondary file-button">
          {{ t('settings.upload') }}
          <input type="file" accept=".csv,text/csv" @change="upload($event, 'fx', 'OPENDAVKI_FX_FILE')" />
        </label>
      </div>

      <h3>{{ t('settings.fees') }}</h3>
      <label class="check">
        <input v-model="form.OPENDAVKI_INCLUDE_FEES" type="checkbox" true-value="true" false-value="false" />
        <span>{{ t('settings.includeFees') }}</span>
      </label>
      <div class="row">
        <div>
          <label for="timezone">{{ t('settings.timezone') }}</label>
          <input id="timezone" v-model="form.OPENDAVKI_TIMEZONE" type="text" />
        </div>
        <div>
          <label for="interest-code">{{ t('settings.interestCode') }}</label>
          <input id="interest-code" v-model="form.OPENDAVKI_INTEREST_TYPE_CODE" type="text" />
        </div>
      </div>

      <h3>{{ t('settings.files') }}</h3>
      <label for="instruments">{{ t('settings.instruments') }}</label>
      <div class="file-row">
        <input id="instruments" v-model="form.OPENDAVKI_INSTRUMENTS_FILE" type="text" />
        <button v-if="desktop" class="secondary" type="button" @click="chooseUpload('instruments', 'OPENDAVKI_INSTRUMENTS_FILE')">{{ t('settings.upload') }}</button>
        <label v-else class="secondary file-button">
          {{ t('settings.upload') }}
          <input type="file" accept=".csv,text/csv" @change="upload($event, 'instruments', 'OPENDAVKI_INSTRUMENTS_FILE')" />
        </label>
      </div>
      <label for="overrides">{{ t('settings.overrides') }}</label>
      <div class="file-row">
        <input id="overrides" v-model="form.OPENDAVKI_OVERRIDES_FILE" type="text" />
        <button v-if="desktop" class="secondary" type="button" @click="chooseUpload('overrides', 'OPENDAVKI_OVERRIDES_FILE')">{{ t('settings.upload') }}</button>
        <label v-else class="secondary file-button">
          {{ t('settings.upload') }}
          <input type="file" accept=".csv,text/csv" @change="upload($event, 'overrides', 'OPENDAVKI_OVERRIDES_FILE')" />
        </label>
      </div>
      <label for="profile">{{ t('settings.profile') }}</label>
      <div class="file-row">
        <button v-if="desktop" class="secondary" type="button" @click="chooseUpload('profile', null)">{{ t('settings.upload') }}</button>
        <label v-else class="secondary file-button">
          {{ t('settings.upload') }}
          <input id="profile" type="file" accept=".json,application/json" @change="upload($event, 'profile', null)" />
        </label>
      </div>

      <div v-if="state.files" class="small muted">
        <p>{{ t('settings.currentFiles') }}:</p>
        <ul>
          <template v-for="(files, kind) in state.files" :key="kind">
            <li v-for="f in files" :key="f.path"><code>{{ kind }}/{{ f.name }}</code></li>
          </template>
          <li v-if="!Object.values(state.files).some((f) => f.length)">{{ t('settings.noFiles') }}</li>
        </ul>
      </div>

      <div class="banner warn">{{ t('settings.warning') }}</div>
      <div class="actions">
        <button class="primary" type="submit" :disabled="busy">{{ busy ? t('report.working') : t('settings.save') }}</button>
        <button class="secondary" type="button" @click="emit('close')">{{ t('settings.close') }}</button>
      </div>
    </form>
  </section>
</template>
