import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Appearance } from './appearance';

describe('Appearance', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.removeItem('arpeon.theme.v1');
    delete document.documentElement.dataset['theme'];
    TestBed.resetTestingModule();
  });

  it('applies and restores a saved theme without changing workspace preferences', () => {
    localStorage.setItem('arpeon.layout.v1', '{"zoom":125}');
    const appearance = TestBed.inject(Appearance);
    expect(document.documentElement.dataset['theme']).toBe('dark');
    appearance.setTheme('light');
    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(localStorage.getItem('arpeon.theme.v1')).toBe('light');
    expect(localStorage.getItem('arpeon.layout.v1')).toBe('{"zoom":125}');
    TestBed.resetTestingModule();
    expect(TestBed.inject(Appearance).theme()).toBe('light');
    localStorage.removeItem('arpeon.layout.v1');
  });

  it('ignores invalid preferences and remains usable when storage is unavailable', () => {
    localStorage.setItem('arpeon.theme.v1', 'unknown');
    const appearance = TestBed.inject(Appearance);
    expect(appearance.theme()).toBe('dark');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage blocked', 'SecurityError');
    });
    expect(() => appearance.setTheme('light')).not.toThrow();
    expect(document.documentElement.dataset['theme']).toBe('light');
  });
});
