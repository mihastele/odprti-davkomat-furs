// End-to-end check in a real browser against the real local server. The CI test job installs Playwright's Chromium first.
//   npm --prefix frontend run build && node frontend/e2e/smoke.cjs [screenshot-dir]
const { chromium } = require('playwright')
const { spawn } = require('child_process')
const assert = require('assert')
const fs = require('fs')
const os = require('os')
const path = require('path')

const root = path.resolve(__dirname, '..', '..')
const shots = process.argv[2] || path.join(root, 'frontend', 'e2e', 'out')
fs.mkdirSync(shots, { recursive: true })

// A throwaway settings file: the settings-screen test must not edit the example in the repository.
const settings = path.join(os.tmpdir(), `opendavki-e2e-${process.pid}.env`)
fs.writeFileSync(settings, `OPENDAVKI_FX_SOURCE=FILE\nOPENDAVKI_FX_FILE=${path.join(root, 'examples', 'rates-synthetic.csv')}\n`)

function startServer() {
  return new Promise((resolve, reject) => {
    // No --web-dir or --schema-dir: this checks the self-contained jar, not the development checkout.
    const p = spawn('java', ['-jar', 'backend/cli/target/opendavki.jar', 'serve', '--no-open',
      '--env-file', settings], { cwd: root, env: { ...process.env, JAVA_TOOL_OPTIONS: '' } })
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

    // The settings screen writes only after an explicit Save, and the value reaches the temporary settings file.
    await page.getByRole('button', { name: 'Settings', exact: true }).click()
    await page.waitForSelector('#settings-h')
    await page.fill('#timezone', 'Europe/Ljubljana')
    await page.getByRole('button', { name: 'Save settings', exact: true }).click()
    await page.waitForSelector('text=Settings saved.')
    assert.match(fs.readFileSync(settings, 'utf8'), /OPENDAVKI_TIMEZONE=Europe\/Ljubljana/)
    await page.getByRole('button', { name: 'Close', exact: true }).first().click()

    await page.setInputFiles('#files', path.join(root, 'examples', 'statement-generic.csv'))
    await page.getByRole('button', { name: 'Import', exact: true }).click()
    await page.waitForSelector('text=2. Check what was read')
    assert.match(await page.textContent('main'), /Ledger entries \(6\)/)
    await page.screenshot({ path: path.join(shots, '2-check.png'), fullPage: true })

    // A reload must keep the token (sessionStorage) and resume the server-side ledger, not throw the user back to the start.
    await page.reload()
    await page.waitForSelector('text=2. Check what was read')
    assert.match(await page.textContent('main'), /Ledger entries \(6\)/)
    assert.ok(!page.url().includes('token'), 'the key must stay out of the address bar after a reload')

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

    // Explicit project save and open: the tax number is included only after ticking the box.
    await page.check('#include-tax')
    const [projectDownload] = await Promise.all([page.waitForEvent('download'),
      page.getByRole('button', { name: 'Save project', exact: true }).click()])
    assert.strictEqual(projectDownload.suggestedFilename(), 'opendavki-2024.opendavki.json')
    const projectPath = await projectDownload.path()
    const project = fs.readFileSync(projectPath, 'utf8')
    assert.match(project, /"formatVersion"\s*:\s*1/)
    assert.match(project, /"taxNumber"\s*:\s*"12345678"/)

    await page.click('text=1. Statements')
    await page.setInputFiles('#project-file', projectPath)
    await page.waitForSelector('text=2. Check what was read')
    await page.click('text=Continue to the report')
    assert.strictEqual(await page.inputValue('#year'), '2024')
    assert.strictEqual(await page.inputValue('#tax'), '12345678')

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
    fs.rmSync(settings, { force: true })
  }
})().catch((e) => { console.error('FAILED:', e.message); process.exit(1) })
