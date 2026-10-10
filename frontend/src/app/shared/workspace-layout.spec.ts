import { beforeEach, describe, expect, it } from 'vitest';
import { WorkspaceLayout } from './workspace-layout';

describe('Workspace layout preferences', () => {
  beforeEach(() => localStorage.clear());

  it('restores bounded preferences and resets to a usable workspace', () => {
    const layout = new WorkspaceLayout();
    layout.toggle('explorer');
    layout.setWidth(900);
    layout.setZoom(10);
    const restored = new WorkspaceLayout();
    expect(restored.explorer()).toBe(false);
    expect(restored.inspectorWidth()).toBe(460);
    expect(restored.zoom()).toBe(50);
    restored.reset();
    const reset = new WorkspaceLayout();
    expect(reset.explorer()).toBe(true);
    expect(reset.inspector()).toBe(true);
    expect(reset.zoom()).toBe(100);
    expect(reset.inspectorWidth()).toBe(320);
  });

  it('ignores corrupt values rather than breaking the editor', () => {
    localStorage.setItem('arpeon.layout.v1', '{broken');
    expect(new WorkspaceLayout().zoom()).toBe(100);
    localStorage.setItem(
      'arpeon.layout.v1',
      JSON.stringify({ zoom: 'huge', inspector: 'false', inspectorWidth: -30 }),
    );
    const layout = new WorkspaceLayout();
    expect(layout.inspector()).toBe(true);
    expect(layout.inspectorWidth()).toBe(280);
    layout.setZoom(Number.NaN);
    expect(layout.zoom()).toBe(100);
  });
});
