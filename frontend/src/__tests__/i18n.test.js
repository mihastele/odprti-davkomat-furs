import { afterEach, describe, expect, it } from 'vitest'
import { locale, resetLocaleForTests, setLocale, t } from '../i18n.js'

afterEach(() => resetLocaleForTests())

describe('i18n', () => {
  it('has English and Slovenian UI strings and falls back to the key', () => {
    expect(t('app.title')).toBe('OpenDavki')
    expect(t('report.year')).toBe('Tax year')
    setLocale('sl')
    expect(locale.value).toBe('sl')
    expect(t('report.year')).toBe('Davčno leto')
    expect(document.documentElement.lang).toBe('sl')
    expect(t('missing.key')).toBe('missing.key')
  })

  it('ignores an unknown locale', () => {
    setLocale('xx')
    expect(locale.value).toBe('en')
  })
})
