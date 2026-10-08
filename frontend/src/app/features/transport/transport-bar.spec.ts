import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ScoreSession } from '../score/score-session';
import { TransportBar } from './transport-bar';

describe('TransportBar', () => {
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
