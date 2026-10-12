import { Component, input } from '@angular/core';

export type IconName =
  | 'score'
  | 'import'
  | 'practice'
  | 'tuner'
  | 'analysis'
  | 'panel'
  | 'help'
  | 'play'
  | 'pause'
  | 'stop'
  | 'folder'
  | 'save'
  | 'menu'
  | 'settings'
  | 'edit'
  | 'study'
  | 'previous'
  | 'next'
  | 'rewind'
  | 'forward';
const PATHS: Record<IconName, string> = {
  edit: 'M3 14l-1 4 4-1L17 6l-3-3z M11 6l3 3',
  study: 'M1 7l9-5 9 5-9 5z M4 9v6c4 3 8 3 12 0V9 M19 7v8',
  previous: 'M4 3v14 M16 3l-10 7 10 7z',
  next: 'M16 3v14 M4 3l10 7-10 7z',
  rewind: 'M9 4l-7 6 7 6z M18 4l-7 6 7 6z',
  forward: 'M2 4l7 6-7 6z M11 4l7 6-7 6z',
  score: 'M4 3h12v14H4z M7 7h6 M7 10h6 M7 13h3',
  import: 'M10 2v10 m-4-4 4 4 4-4 M3 12v5h14v-5',
  practice: 'M7 14V4l9-2v10 M7 7l9-2 M7 14c0 3-5 4-5 1s5-4 5-1 M16 12c0 3-5 4-5 1s5-4 5-1',
  tuner: 'M6 2v6a4 4 0 0 0 8 0V2 M10 12v6 M4 18h12',
  analysis: 'M2 10h2l2-6 3 12 3-14 3 12 2-4h2',
  panel: 'M2 3h16v14H2z M12 3v14',
  help: 'M7 7a3 3 0 1 1 5 2c-2 1-2 2-2 3 M10 15v.1 M10 1a9 9 0 1 0 0 18 9 9 0 0 0 0-18',
  play: 'M6 3l11 7-11 7z',
  pause: 'M6 3v14 M14 3v14',
  stop: 'M4 4h12v12H4z',
  folder: 'M2 5h6l2 2h8v10H2z M2 5V3h6l2 2h6v2',
  save: 'M3 2h12l3 3v13H3z M6 2v6h8V2 M6 18v-7h9v7',
  menu: 'M3 5h14 M3 10h14 M3 15h14',
  settings: 'M3 5h4 M11 5h6 M3 15h8 M15 15h2 M7 3v4 M11 3v4 M11 13v4 M15 13v4',
};
@Component({
  selector: 'app-icon',
  host: { 'aria-hidden': 'true' },
  template:
    '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="paths[name()]" /></svg>',
  styles:
    ':host { display: inline-flex; width: 18px; height: 18px; flex: 0 0 auto; } svg { width: 100%; height: 100%; }',
})
export class Icon {
  readonly name = input.required<IconName>();
  protected readonly paths = PATHS;
}
