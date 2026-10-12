import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ScoreSession } from '../score/score-session';
import { TransportBar } from './transport-bar';

describe('TransportBar', () => {
  it('distinguishes repeated measures, rejects reversed A–B and bounds the seek slider', async () => {
    const action = vi.fn().mockResolvedValue(undefined);
    const state = signal({
      status: 'STOPPED',
      tick: 0,
      totalTicks: 960,
      measureNumber: '1',
      measureIndex: 0,
      bpm: 72,
      startingBpm: 72,
      metronome: false,
      countIn: false,
      countInBeatsLeft: 0,
      loop: null as {
        startTick: number;
        endTick: number;
        startMeasureIndex: number;
        endMeasureIndex: number;
      } | null,
    });
    await TestBed.configureTestingModule({
      imports: [TransportBar],
      providers: [
        {
          provide: ScoreSession,
          useValue: {
            score: signal({
              title: 'Repeat',
              sha256: 'test',
              measures: [
                { index: 0, number: '1' },
                { index: 1, number: '1' },
              ],
            }),
            transport: state,
            busy: signal(false),
            error: signal(''),
            action,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(TransportBar);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement as HTMLElement;
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Trecho A–B')
      ?.click();
    fixture.detectChanges();
    const selects = root.querySelectorAll<HTMLSelectElement>('.loop-controls select');
    const start = selects[0];
    const end = selects[1];
    if (!start || !end) throw new Error('Both A–B controls must be rendered');
    expect(selects[0]?.options[0]?.textContent).toContain('Posição 1 · compasso 1');
    expect(selects[0]?.options[1]?.textContent).toContain('Posição 2 · compasso 1');
    start.value = '1';
    start.dispatchEvent(new Event('change'));
    end.value = '0';
    end.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const apply = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Repetir trecho',
    );
    expect(apply?.disabled).toBe(true);
    expect(action).not.toHaveBeenCalled();
    end.value = '1';
    end.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    apply?.click();
    await fixture.whenStable();
    expect(action).toHaveBeenCalledWith('loop', {
      startMeasureIndex: 1,
      endMeasureIndex: 1,
      enabled: true,
    });
    state.set({
      ...state(),
      tick: 480,
      loop: { startTick: 480, endTick: 960, startMeasureIndex: 1, endMeasureIndex: 1 },
    });
    fixture.detectChanges();
    const seek = root.querySelector<HTMLInputElement>('input[type=range]');
    expect(seek?.min).toBe('480');
    expect(seek?.max).toBe('959');
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Desativar repetição')
      ?.click();
    await fixture.whenStable();
    expect(action).toHaveBeenLastCalledWith('loop', {
      startMeasureIndex: 1,
      endMeasureIndex: 1,
      enabled: false,
    });
  });

  it('applies an edited BPM only after the explicit action', async () => {
    const action = vi.fn().mockResolvedValue(undefined);
    await TestBed.configureTestingModule({
      imports: [TransportBar],
      providers: [
        {
          provide: ScoreSession,
          useValue: {
            score: signal({ measures: [{ number: '1' }] }),
            transport: signal({
              status: 'STOPPED',
              tick: 0,
              totalTicks: 1920,
              measureNumber: '1',
              bpm: 72,
              startingBpm: 72,
              metronome: false,
              countIn: false,
              countInBeatsLeft: 0,
            }),
            busy: signal(false),
            error: signal(''),
            action,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(TransportBar);
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#starting-bpm') as HTMLInputElement;
    input.value = '96';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(action).not.toHaveBeenCalled();
    const apply = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent === 'Aplicar');
    expect(apply?.disabled).toBe(false);
    apply?.click();
    await fixture.whenStable();
    expect(action).toHaveBeenCalledWith('bpm', { bpm: 96 });
  });
});
