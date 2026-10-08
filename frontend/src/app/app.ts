import { Component, signal } from '@angular/core';
import { TunerPanel } from './features/tuner/tuner-panel';
import { ScoreWorkspace } from './features/score/score-workspace';
import { ScoreSession } from './features/score/score-session';
import { TransportBar } from './features/transport/transport-bar';
import { PracticePanel } from './features/practice/practice-panel';
import { ImportPanel } from './features/import/import-panel';

@Component({
  selector: 'app-root',
  imports: [TunerPanel, ScoreWorkspace, TransportBar, PracticePanel, ImportPanel],
  providers: [ScoreSession],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  readonly view = signal<'score' | 'tuner' | 'practice' | 'import'>('score');
}
