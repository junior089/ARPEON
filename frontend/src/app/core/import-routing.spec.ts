import { describe, expect, it } from 'vitest';
import { importRoute } from './import-routing';
describe('file routing', () => {
  it('routes structured files directly and scanned music to recognition', () => {
    for (const file of ['score.XML', 'score.musicxml', 'score.mxl', 'score.mscz'])
      expect(importRoute(file)).toBe('direct');
    for (const file of ['score.pdf', 'score.PNG', 'score.jpeg', 'score.jpg'])
      expect(importRoute(file)).toBe('recognition');
    for (const file of ['score.mid', 'score.midi', 'score.wav'])
      expect(importRoute(file)).toBe('draft');
    for (const file of ['score.tiff', 'score.mp3', 'score.pdf.exe', 'score'])
      expect(importRoute(file)).toBe('unsupported');
  });
});
