import type { IOSMDOptions } from 'opensheetmusicdisplay';

export function scoreAppearance(dark = false): IOSMDOptions {
  const style = getComputedStyle(document.documentElement);
  const token = (name: string): string => style.getPropertyValue(name).trim();
  return {
    darkMode: dark,
    pageBackgroundColor: token(dark ? '--color-score-dark-paper' : '--color-score-paper'),
    defaultColorMusic: token(dark ? '--color-score-dark-ink' : '--color-score-ink'),
    cursorsOptions: [
      {
        type: 1,
        color: token(dark ? '--color-score-dark-cursor' : '--color-score-cursor'),
        alpha: 1,
        follow: true,
      },
    ],
  };
}
