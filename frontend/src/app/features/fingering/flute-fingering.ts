import { Component, computed, effect, input, signal } from '@angular/core';
import { FLUTE_KEYS, fluteFingerings, FluteKey } from './flute-data';

@Component({
  selector: 'app-flute-fingering',
  templateUrl: './flute-fingering.html',
  styleUrl: './flute-fingering.scss',
})
export class FluteFingering {
  readonly pitch = input<string | null>(null);
  readonly referenceGuide = input(false);
  readonly referencePitch = signal<string | null>(null);
  readonly position = computed(() =>
    fluteFingerings(this.referencePitch() ?? this.pitch() ?? (this.referenceGuide() ? 'C4' : null)),
  );
  readonly keys = FLUTE_KEYS;
  readonly variant = signal('standard');
  readonly inspected = signal<FluteKey | null>(null);
  readonly basicNotes = [
    { pitch: 'C4', name: 'Dó', letter: 'C' },
    { pitch: 'C#4', name: 'Dó♯', letter: 'C♯' },
    { pitch: 'D4', name: 'Ré', letter: 'D' },
    { pitch: 'D#4', name: 'Ré♯', letter: 'D♯' },
    { pitch: 'E4', name: 'Mi', letter: 'E' },
    { pitch: 'F4', name: 'Fá', letter: 'F' },
    { pitch: 'F#4', name: 'Fá♯', letter: 'F♯' },
    { pitch: 'G4', name: 'Sol', letter: 'G' },
    { pitch: 'G#4', name: 'Sol♯', letter: 'G♯' },
    { pitch: 'A4', name: 'Lá', letter: 'A' },
    { pitch: 'A#4', name: 'Lá♯', letter: 'A♯' },
    { pitch: 'B4', name: 'Si', letter: 'B' },
    { pitch: 'C5', name: 'Dó', letter: 'C · 5' },
  ].map((note, index) => ({
    ...note,
    midi: 60 + index,
    staffY: [30, 30, 27.5, 27.5, 25, 22.5, 22.5, 20, 20, 17.5, 17.5, 15, 12.5][index],
    pressed: fluteFingerings(note.pitch)?.variants[0]?.pressed ?? [],
  }));
  readonly current = computed(
    () =>
      this.position()?.variants.find((v) => v.id === this.variant()) ??
      this.position()?.variants[0],
  );
  readonly inspectedLabel = computed(() => {
    const id = this.inspected();
    return id
      ? `${this.keys.find((k) => k.id === id)?.label}: ${this.pressed(id) ? 'pressionada' : 'aberta'}`
      : '';
  });
  constructor() {
    effect(() => {
      this.pitch();
      this.referencePitch.set(null);
    });
    effect(() => {
      this.position();
      this.variant.set('standard');
      this.inspected.set(null);
    });
  }
  pressed(key: FluteKey): boolean {
    return this.current()?.pressed.includes(key) ?? false;
  }
  inspect(key: FluteKey): void {
    this.inspected.set(key);
  }
  keyName(key: FluteKey): string {
    return key === 'Gs'
      ? 'G♯'
      : key === 'Cs'
        ? 'C♯'
        : key === 'Bb'
          ? 'B♭'
          : key === 'Eb'
            ? 'E♭'
            : key;
  }
  rightHand(key: FluteKey): boolean {
    return ['R1', 'R2', 'R3', 'Eb', 'Cs', 'C'].includes(key);
  }
  diagramX(key: FluteKey): number {
    return {
      L1: 28,
      L2: 68,
      L3: 108,
      T: 28,
      Bb: 68,
      Gs: 108,
      R1: 180,
      R2: 220,
      R3: 260,
      Eb: 180,
      Cs: 220,
      C: 260,
    }[key];
  }
  diagramY(key: FluteKey): number {
    return ['L1', 'L2', 'L3', 'R1', 'R2', 'R3'].includes(key) ? 45 : 96;
  }
  chooseReference(pitch: string): void {
    this.referencePitch.set(pitch);
  }
}
