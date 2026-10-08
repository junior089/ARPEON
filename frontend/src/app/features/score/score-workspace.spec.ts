import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ScoreSession } from './score-session';
import { ScoreWorkspace } from './score-workspace';

describe('ScoreWorkspace editor', () => {
  it('selects a score event and sends an explicit pitch edit', async () => {
    const edit = vi.fn().mockResolvedValue(false);
    await TestBed.configureTestingModule({
      imports: [ScoreWorkspace],
      providers: [
        {
          provide: ScoreSession,
          useValue: {
            score: signal({
              title: 'Test',
              filename: 'test.musicxml',
              sourceKind: 'MUSICXML',
              importWarnings: [],
              revision: 0,
              canUndo: false,
              canRedo: false,
              keyId: null,
              measures: [{ index: 0, number: '1' }],
              rangeWarnings: [],
              instruments: [{ id: 'concert', name: 'Som genérico' }],
              keys: [{ id: 'C_MAJOR', label: 'C maior' }],
              parts: [
                {
                  id: 'P1',
                  name: 'Melodia',
                  measures: [
                    {
                      index: 0,
                      number: '1',
                      events: [
                        {
                          index: 0,
                          kind: 'NOTE',
                          pitch: 'C4',
                          step: 'C',
                          alter: 0,
                          octave: 4,
                          numerator: 1,
                          denominator: 4,
                        },
                      ],
                    },
                  ],
                },
              ],
            }),
            transport: signal({
              tick: 0,
              activePartId: 'P1',
              parts: [{ id: 'P1', name: 'Melodia', instrumentId: 'concert' }],
            }),
            xml: signal(''),
            preview: signal(null),
            busy: signal(false),
            error: signal(''),
            edit,
            action: vi.fn(),
            export: vi.fn(),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ScoreWorkspace);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement as HTMLElement;
    const changePitch = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.trim() === 'Alterar nota',
    );
    expect(changePitch?.disabled).toBe(false);
    changePitch?.click();
    await fixture.whenStable();
    expect(edit).toHaveBeenCalledWith('set-pitch', {
      partId: 'P1',
      measureIndex: 0,
      eventIndex: 0,
      step: 'C',
      alter: 0,
      octave: 4,
    });
    const keyPreview = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.includes('Prévia para tom'),
    );
    expect(keyPreview?.disabled).toBe(true);
  });
});
