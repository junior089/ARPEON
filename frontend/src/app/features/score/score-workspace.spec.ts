import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ScoreSession } from './score-session';
import { ScoreWorkspace } from './score-workspace';

describe('ScoreWorkspace editor', () => {
  it('selects a score event and sends an explicit pitch edit', async () => {
    const edit = vi.fn().mockResolvedValue(false);
    const events = [
      {
        index: 0,
        kind: 'CHORD',
        pitch: 'C4 + E4',
        step: 'C',
        alter: 0,
        octave: 4,
        numerator: 1,
        denominator: 4,
        tieStart: false,
        tieStop: false,
        heads: [
          { index: 0, pitch: 'C4', tieStart: false, tieStop: false },
          { index: 1, pitch: 'E4', tieStart: false, tieStop: false },
        ],
      },
      {
        index: 1,
        kind: 'NOTE',
        pitch: 'D4',
        step: 'D',
        alter: 0,
        octave: 4,
        numerator: 1,
        denominator: 4,
        tieStart: false,
        tieStop: false,
      },
      ...Array.from({ length: 50 }, (_, index) => ({
        index: index + 2,
        kind: 'NOTE',
        pitch: 'E4',
        step: 'E',
        alter: 0,
        octave: 4,
        numerator: 1,
        denominator: 4,
        tieStart: false,
        tieStop: false,
      })),
    ];
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
              source: null,
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
                      events,
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
            opening: signal(false),
            savingProject: signal(false),
            projects: signal([]),
            projectId: signal(null),
            projectDirty: signal(false),
            error: signal(''),
            refreshProjects: vi.fn().mockResolvedValue(undefined),
            loadMeasureEvents: vi.fn().mockResolvedValue(true),
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
    expect(root.querySelector<HTMLSelectElement>('.zoom-control select')?.value).toBe('100');
    const duration = Array.from(root.querySelectorAll('label')).find((label) =>
      label.textContent?.trim().startsWith('Denominador'),
    );
    expect(duration?.querySelector('select')?.value).toBe('4');
    const separator = root.querySelector<HTMLElement>('[role="separator"]');
    separator?.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    fixture.detectChanges();
    expect(separator?.getAttribute('aria-valuenow')).toBe('460');
    separator?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    fixture.detectChanges();
    expect(separator?.getAttribute('aria-valuenow')).toBe('280');
    const editor = root.querySelector<HTMLDetailsElement>('details.editor');
    expect(editor).not.toBeNull();
    if (editor) editor.open = true;
    const changePitch = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.trim() === 'Alterar nota',
    );
    expect(changePitch?.disabled).toBe(false);
    changePitch?.click();
    await fixture.whenStable();
    expect(edit).toHaveBeenCalledWith('set-pitch', {
      partId: 'P1',
      measureIndex: 0,
      voiceId: '1',
      eventIndex: 0,
      headIndex: 0,
      step: 'C',
      alter: 0,
      octave: 4,
    });
    const secondHead = Array.from(
      root.querySelectorAll<HTMLButtonElement>('.chord-heads button'),
    )[1];
    expect(secondHead?.textContent).toContain('E4');
    secondHead?.click();
    fixture.detectChanges();
    changePitch?.click();
    await fixture.whenStable();
    expect(edit).toHaveBeenLastCalledWith('set-pitch', {
      partId: 'P1',
      measureIndex: 0,
      voiceId: '1',
      eventIndex: 0,
      headIndex: 1,
      step: 'E',
      alter: 0,
      octave: 4,
    });
    const keyPreview = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.includes('Prévia para tom'),
    );
    expect(keyPreview?.disabled).toBe(true);
    let secondNote = root.querySelectorAll<HTMLButtonElement>('.study-notes button')[1];
    expect(secondNote).toBeDefined();
    expect(root.querySelectorAll('.study-notes button')).toHaveLength(48);
    const nextPage = Array.from(
      root.querySelectorAll<HTMLButtonElement>('.study-pagination button'),
    ).find((button) => button.textContent?.includes('Próxima'));
    nextPage?.click();
    fixture.detectChanges();
    expect(root.querySelector('.study-pagination')?.textContent).toContain('Página 2 de 2');
    expect(root.querySelectorAll('.study-notes button')).toHaveLength(4);
    expect(root.querySelector('.study-notes button .note-order')?.textContent).toBe('49');
    fixture.componentInstance.selectEvent(1);
    fixture.detectChanges();
    expect(root.querySelectorAll('.study-notes button')).toHaveLength(48);
    secondNote = root.querySelectorAll<HTMLButtonElement>('.study-notes button')[1];
    fixture.componentInstance.focusStudyNotes();
    expect(document.activeElement).toBe(root.querySelector('.study-notes button'));
    secondNote?.click();
    fixture.detectChanges();
    expect(root.querySelector('app-flute-fingering')?.textContent).toContain('Ré 4');

    fixture.componentInstance.selectEvent(0);
    const graphicalMeasure = {};
    (fixture.componentInstance as unknown as { renderer: unknown }).renderer = {
      clear: vi.fn(),
      GraphicSheet: {
        domToSvg: (point: unknown) => point,
        svgToOsmd: (point: unknown) => point,
        GetNearestNote: () => ({
          PositionAndShape: { AbsolutePosition: { x: 0, y: 0 } },
          parentVoiceEntry: {
            parentStaffEntry: {
              parentMeasure: graphicalMeasure,
              relInMeasureTimestamp: { RealValue: 0.25 },
            },
          },
        }),
        MeasureList: [[graphicalMeasure]],
      },
    };
    const svgNote = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const scoreClick = new MouseEvent('click');
    Object.defineProperty(scoreClick, 'target', { value: svgNote });
    await fixture.componentInstance.selectRenderedNote(scoreClick);
    fixture.detectChanges();
    expect(root.querySelector('app-flute-fingering')?.textContent).toContain('Ré 4');
  });
});
