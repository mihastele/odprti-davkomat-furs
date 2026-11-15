// All traffic goes to the local `opendavki serve` process that delivered this page. The key it printed is in the address
// fragment (#token=...), which the browser never sends to a server; it is kept in memory and removed from the address bar.

const TOKEN_KEY = 'opendavki.token'
const LEDGER_KEY = 'opendavki.ledgerId'
let token = null

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

/** sessionStorage survives a reload but is cleared when the tab closes; private modes may not have it. */
function safeStorage() {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

/** Reads the key from the address fragment, then hides it and keeps it for a reload. Returns whether a key is available. */
export function initToken(location = window.location, history = window.history, storage = safeStorage()) {
  const match = /(?:^#|&)token=([^&]+)/.exec(location.hash)
  if (match) {
    token = decodeURIComponent(match[1])
    history.replaceState(null, '', location.pathname + location.search)
    try { storage?.setItem(TOKEN_KEY, token) } catch { /* private mode: keep the in-memory key */ }
  } else if (token === null) {
    try { token = storage?.getItem(TOKEN_KEY) ?? null } catch { token = null }
  }
  return token !== null
}

export function hasToken() {
  return token !== null
}

export function resetTokenForTests(storage = safeStorage()) {
  token = null
  forgetLedger(storage)
  try { storage?.removeItem(TOKEN_KEY) } catch { /* ignore */ }
}

/** The server keeps the ledger in memory; remembering the id lets a reload resume the check screen. */
export function rememberLedger(ledgerId, storage = safeStorage()) {
  try { if (ledgerId) storage?.setItem(LEDGER_KEY, ledgerId) } catch { /* ignore */ }
}

export function rememberedLedger(storage = safeStorage()) {
  try { return storage?.getItem(LEDGER_KEY) ?? null } catch { return null }
}

export function forgetLedger(storage = safeStorage()) {
  try { storage?.removeItem(LEDGER_KEY) } catch { /* ignore */ }
}

export async function call(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { 'X-Opendavki-Token': token ?? '', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined
  })
  let data = {}
  try {
    data = await res.json()
  } catch {
    // an empty or non-JSON body: the status line below is enough
  }
  if (!res.ok) throw new ApiError(data.error || `${res.status} ${res.statusText}`, res.status)
  return data
}

/** GET returning text (used for the exact ledger JSON, which must not pass through JavaScript numbers). */
export async function fetchText(path) {
  const res = await fetch(path, { headers: { 'X-Opendavki-Token': token ?? '' } })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try { message = (await res.json()).error || message } catch { /* keep the status line */ }
    throw new ApiError(message, res.status)
  }
  return res.text()
}

/** Base64 of a file's bytes, in chunks so large statements do not overflow the call stack. */
export async function fileToBase64(file) {
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

/** Whether the JavaFX shell injected its file picker bridge. */
export function desktopAvailable() {
  return typeof window !== 'undefined' && typeof window.opendavkiDesktop?.pickFiles === 'function'
}

/** Returns [{name, base64, size}] from the JavaFX FileChooser, or null in a normal browser. */
export function pickDesktopFiles(multiple = true) {
  if (!desktopAvailable()) return null
  try {
    return JSON.parse(window.opendavkiDesktop.pickFiles(multiple, ''))
  } catch {
    return []
  }
}

export function base64ToText(base64) {
  const binary = atob(base64)
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

export function download(filename, text, type = 'text/plain') {
  // JavaFX WebView has no download manager; its shell injects this bridge before the page loads.
  try {
    if (window.opendavkiDesktop?.saveFile) {
      window.opendavkiDesktop.saveFile(filename, text, type)
      return
    }
  } catch { /* fall back to the browser's Blob download */ }
  const url = URL.createObjectURL(new Blob([text], { type: `${type};charset=utf-8` }))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
