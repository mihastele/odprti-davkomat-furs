import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiagnosticsTable from '../components/DiagnosticsTable.vue'

const diagnostics = [
  { severity: 'ERROR', code: 'MISSING_PURCHASE_HISTORY', message: 'short by 2', source: { file: 'a.csv', line: 7 } },
  { severity: 'WARNING', code: 'TRANSFER_UNPAIRED', message: 'no counterpart', source: null },
  { severity: 'INFO', code: 'TRANSFER_PAIRED', message: 'paired', source: { file: 'a.csv', line: 9 } }
]

describe('DiagnosticsTable', () => {
  it('shows errors and warnings by default and counts every level', () => {
    const w = mount(DiagnosticsTable, { props: { diagnostics } })
    const buttons = w.findAll('button').map((b) => b.text())
    expect(buttons).toEqual(['ERROR (1)', 'WARNING (1)', 'INFO (1)'])
    const rows = w.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('MISSING_PURCHASE_HISTORY')
    expect(rows[0].text()).toContain('a.csv:7')
    expect(rows[1].text()).toContain('-') // no source
    expect(w.text()).not.toContain('TRANSFER_PAIRED')
  })

  it('shows info notes when asked and hides a level again', async () => {
    const w = mount(DiagnosticsTable, { props: { diagnostics } })
    await w.findAll('button')[2].trigger('click')
    expect(w.findAll('tbody tr')).toHaveLength(3)
    await w.findAll('button')[0].trigger('click')
    expect(w.findAll('tbody tr')).toHaveLength(2)
    expect(w.text()).not.toContain('MISSING_PURCHASE_HISTORY')
  })

  it('says so when there is nothing', () => {
    expect(mount(DiagnosticsTable, { props: { diagnostics: [] } }).text()).toContain('No findings.')
  })

  it('escapes message text instead of interpreting it as HTML', () => {
    const w = mount(DiagnosticsTable, { props: { diagnostics: [{ severity: 'ERROR', code: 'X', message: '<img src=x onerror=alert(1)>', source: null }] } })
    expect(w.find('img').exists()).toBe(false)
    expect(w.text()).toContain('<img src=x onerror=alert(1)>')
  })
})
