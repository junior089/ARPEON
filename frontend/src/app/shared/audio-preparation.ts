import { Component, computed, effect, inject, input, OnDestroy } from '@angular/core';
import { AudioSettings, validReference } from '../core/audio-settings';
import { ReferenceTone } from '../core/reference-tone';

@Component({
  selector: 'app-audio-preparation',
  templateUrl: './audio-preparation.html',
  styleUrl: './audio-preparation.scss',
})
export class AudioPreparation implements OnDestroy {
  readonly audio = inject(AudioSettings);
  readonly tone = inject(ReferenceTone);
  readonly captureActive = input(false);
  readonly busy = input(false);
  readonly locked = computed(() => this.captureActive() || this.busy() || this.audio.loading());
  readonly validReference = validReference;
  constructor() {
    effect(() => {
      if (this.captureActive() || this.busy()) this.tone.stop();
    });
  }
  ngOnDestroy(): void {
    this.tone.stop();
  }
}
