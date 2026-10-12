import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WorkspaceLayout } from './workspace-layout';

describe('Workspace layout preferences', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  function restore(): WorkspaceLayout {
    TestBed.resetTestingModule();
    return TestBed.inject(WorkspaceLayout);
  }

  it('restores bounded preferences and resets to a usable workspace', () => {
    const layout = restore();
    layout.toggle('explorer');
    layout.setWidth(900);
    layout.setZoom(10);
    const restored = restore();
    expect(restored.explorer()).toBe(false);
    expect(restored.inspectorWidth()).toBe(460);
    expect(restored.zoom()).toBe(50);
    restored.reset();
    const reset = restore();
    expect(reset.explorer()).toBe(true);
    expect(reset.inspector()).toBe(true);
    expect(reset.zoom()).toBe(100);
    expect(reset.inspectorWidth()).toBe(320);
  });

  it('ignores corrupt values rather than breaking the editor', () => {
    localStorage.setItem('arpeon.layout.v1', '{broken');
    expect(restore().zoom()).toBe(100);
    localStorage.setItem(
      'arpeon.layout.v1',
      JSON.stringify({ zoom: 'huge', inspector: 'false', inspectorWidth: -30 }),
    );
    const layout = restore();
    expect(layout.inspector()).toBe(true);
    expect(layout.inspectorWidth()).toBe(280);
    layout.setZoom(Number.NaN);
    expect(layout.zoom()).toBe(100);
  });

  it('restores each workspace mode and keeps zoom changes independent of compact panels', () => {
    const layout = restore();
    layout.setMode('play');
    expect(layout.explorer()).toBe(false);
    expect(layout.inspector()).toBe(false);
    expect(restore().mode()).toBe('play');
    const editor = restore();
    editor.setMode('edit');
    editor.explorer.set(false); // Temporary compact viewport override.
    editor.setZoom(125);
    editor.restorePanels();
    expect(editor.explorer()).toBe(true);
    expect(restore().explorer()).toBe(true);
    const study = restore();
    study.setMode('study');
    study.toggleScoreTheme();
    const restored = restore();
    expect(restored.mode()).toBe('study');
    expect(restored.scoreDark()).toBe(true);
    expect(restored.inspector()).toBe(true);
  });
});
