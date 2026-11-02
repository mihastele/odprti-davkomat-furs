import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, call, fileToBase64, fetchText, hasToken, initToken, resetTokenForTests } from '../api.js'

afterEach(() => {
  resetTokenForTests()
  vi.restoreAllMocks()
})

describe('initToken', () => {
  it('reads the key from the address fragment and removes it from the address bar', () => {
    const replaceState = vi.fn()
    const ok = initToken({ hash: '#token=abc_DEF-123', pathname: '/app', search: '?x=1' }, { replaceState })
    expect(ok).toBe(true)
    expect(hasToken()).toBe(true)
    expect(replaceState).toHaveBeenCalledWith(null, '', '/app?x=1')
  })

  it('reports that there is no key when the fragment lacks one', () => {
    const replaceState = vi.fn()
    expect(initToken({ hash: '', pathname: '/', search: '' }, { replaceState })).toBe(false)
    expect(initToken({ hash: '#other=1', pathname: '/', search: '' }, { replaceState })).toBe(false)
    expect(replaceState).not.toHaveBeenCalled()
  })
})

describe('call', () => {
  it('sends the key as a header and the body as JSON', async () => {
    initToken({ hash: '#token=k1', pathname: '/', search: '' }, { replaceState() {} })
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ a: 1 }) })
    vi.stubGlobal('fetch', fetchMock)
    expect(await call('POST', '/api/x', { b: 2 })).toEqual({ a: 1 })
    const [path, init] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/x')
    expect(init.method).toBe('POST')
    expect(init.headers['X-Opendavki-Token']).toBe('k1')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.body).toBe('{"b":2}')
  })

  it('turns the server message into an ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', json: async () => ({ error: 'No adapter recognises x.csv' }) }))
    await expect(call('POST', '/api/import', {})).rejects.toMatchObject({ name: 'Error', message: 'No adapter recognises x.csv', status: 400 })
  })

  it('falls back to the status line when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 502, statusText: 'Bad Gateway', json: async () => { throw new Error('no json') } }))
    const err = await call('GET', '/api/info').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.message).toBe('502 Bad Gateway')
  })

  it('fetchText returns the exact body text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, text: async () => '{"q":0.1234567890123456}' }))
    expect(await fetchText('/api/ledger/1/download')).toBe('{"q":0.1234567890123456}')
  })
})

describe('fileToBase64', () => {
  it('encodes small and large files exactly', async () => {
    expect(await fileToBase64(new File(['hello'], 'a.txt'))).toBe('aGVsbG8=')
    const big = new Uint8Array(100_000).map((_, i) => i % 251)
    const decoded = Uint8Array.from(atob(await fileToBase64(new File([big], 'big.bin'))), (c) => c.charCodeAt(0))
    expect(decoded.length).toBe(100_000)
    expect(decoded.every((v, i) => v === i % 251)).toBe(true)
  })
})
