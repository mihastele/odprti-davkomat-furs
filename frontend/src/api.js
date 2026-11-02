// All traffic goes to the local `opendavki serve` process that delivered this page. The key it printed is in the address
// fragment (#token=...), which the browser never sends to a server; it is kept in memory and removed from the address bar.

let token = null

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

/** Reads the key from the address fragment, then hides it. Returns whether a key is available. */
export function initToken(location = window.location, history = window.history) {
  const match = /(?:^#|&)token=([^&]+)/.exec(location.hash)
  if (match) {
    token = decodeURIComponent(match[1])
    history.replaceState(null, '', location.pathname + location.search)
  }
  return token !== null
}

export function hasToken() {
  return token !== null
}

export function resetTokenForTests() {
  token = null
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

export function download(filename, text, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([text], { type: `${type};charset=utf-8` }))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
