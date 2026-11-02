// End-to-end check in a real browser against the real local server. Not part of CI (needs Playwright's Chromium).
//   npm --prefix frontend run build && node frontend/e2e/smoke.cjs [screenshot-dir]
const { chromium } = require('playwright')
const { spawn } = require('child_process')
const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..', '..')
const shots = process.argv[2] || path.join(root, 'frontend', 'e2e', 'out')
fs.mkdirSync(shots, { recursive: true })

function startServer() {
  return new Promise((resolve, reject) => {
    const p = spawn('java', ['-jar', 'backend/cli/target/opendavki.jar', 'serve', '--web-dir', 'frontend/dist',
      '--env-file', 'examples/synthetic.env', '--schema-dir', 'schemas'], { cwd: root, env: { ...process.env, JAVA_TOOL_OPTIONS: '' } })
    let out = ''
    const onData = (d) => {
      out += d
      const m = /(http:\/\/127\.0\.0\.1:\d+\/#token=\S+)/.exec(out)
      if (m) resolve({ proc: p, url: m[1] })
    }
    p.stderr.on('data', onData)
    p.stdout.on('data', onData)
    p.on('exit', (c) => reject(new Error(`server exited early (${c}): ${out}`)))
    setTimeout(() => reject(new Error('server did not start: ' + out)), 20000)
  })
}

;(async () => {
  const { proc, url } = await startServer()
  const browser = await chromium.launch()
  try {
    const page = await (await browser.newContext({ acceptDownloads: true, viewport: { width: 1100, height: 900 } })).newPage()
    const problems = []
    page.on('pageerror', (e) => problems.push(String(e)))
    // the browser logs every non-2xx response; the unrecognised-file step below is meant to produce one (400)
    page.on('console', (m) => { if (m.type() === 'error' && !/status of 400/.test(m.text())) problems.push(m.text()) })

    // without the key the page explains what to do and the API refuses
    await page.goto(url.split('#')[0])
    await page.waitForSelector('.banner.error')
    assert.match(await page.textContent('main'), /address printed by `opendavki serve`/)

    await page.goto(url)
    await page.waitForSelector('text=1. Choose your broker statements')
    assert.match(await page.textContent('main'), /Work in progress/)
    assert.ok(!page.url().includes('token'), 'the key must be removed from the address bar')
    await page.screenshot({ path: path.join(shots, '1-import.png'), fullPage: true })

    await page.setInputFiles('#files', path.join(root, 'examples', 'statement-generic.csv'))
    await page.getByRole('button', { name: 'Import', exact: true }).click()
    await page.waitForSelector('text=2. Check what was read')
    assert.match(await page.textContent('main'), /Ledger entries \(6\)/)
    await page.screenshot({ path: path.join(shots, '2-check.png'), fullPage: true })

    await page.click('text=Continue to the report')
    await page.fill('#year', '2024')
    await page.fill('#tax', '12345678')
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    await page.waitForSelector('text=Complete: no instrument or payment was left out.')
    const text = await page.textContent('main')
    assert.match(text, /valid against the official schema/)
    const gains = await page.locator('section:has-text("3. Create the declarations") table').first().textContent()
    assert.match(gains, /US0378331005\s*AAPL\s*57\.70/)   // 12 x (54.9083 - 50.10), worked out by hand; the cents must be kept
    assert.match(gains, /IE00BK5BQT80\s*VWCE\s*58\.00/)
    assert.match(text, /# Audit report 2024/)
    await page.screenshot({ path: path.join(shots, '3-report.png'), fullPage: true })

    const [download] = await Promise.all([page.waitForEvent('download'), page.click('text=Download Doh-KDVP XML')])
    assert.strictEqual(download.suggestedFilename(), 'kdvp-2024.xml')
    const xml = fs.readFileSync(await download.path(), 'utf8')
    assert.ok(xml.startsWith('<?xml') && xml.includes('<ISIN>US0378331005</ISIN>'))

    // a file nobody recognises: clear message, and the safe layout view
    await page.click('text=1. Statements')
    await page.setInputFiles('#files', { name: 'mystery.csv', mimeType: 'text/csv', buffer: Buffer.from('Foo,Bar\n1,2\n') })
    await page.getByRole('button', { name: 'Import', exact: true }).click()
    await page.waitForSelector('text=No adapter recognises mystery.csv')
    await page.click('text=Show the layout of the first file')
    await page.waitForSelector('text=Layout of mystery.csv')
    await page.screenshot({ path: path.join(shots, '4-unrecognised.png'), fullPage: true })

    assert.deepStrictEqual(problems, [], 'browser errors: ' + problems.join('; '))
    console.log('web interface smoke test: OK (screenshots in ' + shots + ')')
  } catch (e) {
    try { await (await browser.contexts()[0].pages()[0]).screenshot({ path: path.join(shots, 'failure.png'), fullPage: true }) } catch { /* best effort */ }
    try { console.error('PAGE TEXT:', (await browser.contexts()[0].pages()[0].textContent('main')).replace(/\s+/g, ' ').slice(0, 600)) } catch { /* best effort */ }
    throw e
  } finally {
    await browser.close()
    proc.kill()
  }
})().catch((e) => { console.error('FAILED:', e.message); process.exit(1) })
