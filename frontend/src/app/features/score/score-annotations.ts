import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import type { VexFlowGraphicalNote } from 'opensheetmusicdisplay/build/dist/src/MusicalScore/Graphical/VexFlow/VexFlowGraphicalNote';
import { parsePitch, pitchLabel } from './music-reading';
export interface ReadingOptions {
  enabled: boolean;
  language: 'letters' | 'portuguese';
  octave: boolean;
  scope: 'all' | 'selection';
  partId: string;
  measure: number;
  pitch: string | null;
  onset: number;
  voiceId: string;
  dark: boolean;
}
export function annotateScore(
  renderer: OpenSheetMusicDisplay,
  container: HTMLElement,
  options: ReadingOptions,
): void {
  container.querySelectorAll('.reading-layer').forEach((layer) => layer.remove());
  if (!options.enabled) return;
  const boxes = new Map<SVGSVGElement, { x: number; y: number; width: number }[]>();
  renderer.GraphicSheet?.MeasureList.forEach((row, measureIndex) =>
    row.forEach((measure) => {
      measure.staffEntries.forEach((entry) =>
        entry.graphicalVoiceEntries.forEach((voice) =>
          voice.notes.forEach((graphical) => {
            const source = graphical.sourceNote;
            if (source.isRest() || !source.PrintObject) return;
            const partId = source.ParentVoiceEntry.ParentVoice.Parent.IdString;
            const pitch = parsePitch(source.Pitch.ToStringShort(3));
            const selected =
              partId === options.partId &&
              measureIndex === options.measure &&
              String(source.ParentVoiceEntry.ParentVoice.VoiceId) === options.voiceId &&
              Math.abs(entry.relInMeasureTimestamp.RealValue - options.onset) < 0.0001 &&
              pitch &&
              pitchLabel(pitch) === options.pitch?.replaceAll('#', '♯').replaceAll('b', '♭');
            if (!pitch || (options.scope === 'selection' && !selected)) return;
            const note = graphical as VexFlowGraphicalNote;
            if (typeof note.getNoteheadSVGs !== 'function') return;
            const heads = note.getNoteheadSVGs();
            const head = heads[note.vfnoteIndex] ?? heads[0];
            const svg = head?.closest('svg');
            const matrix = svg?.getScreenCTM();
            if (!head || !svg || !matrix) return;
            const rect = head.getBoundingClientRect();
            const point = svg.createSVGPoint();
            point.x = rect.x + rect.width / 2;
            point.y = rect.y;
            const local = point.matrixTransform(matrix.inverse());
            const text = pitchLabel(pitch, options.language, options.octave);
            const width = text.length * 6.5 + 4;
            const occupied = boxes.get(svg) ?? [];
            const stave = note.vfnote?.[0]?.getStave();
            let y = stave ? stave.getYForBottomText(0) + 3 : local.y + 24;
            for (
              let attempt = 0;
              attempt < 3 &&
              occupied.some(
                (b) => Math.abs(b.y - y) < 13 && Math.abs(b.x - local.x) < (b.width + width) / 2,
              );
              attempt++
            )
              y += 14;
            if (
              y < 12 ||
              occupied.some(
                (b) => Math.abs(b.y - y) < 13 && Math.abs(b.x - local.x) < (b.width + width) / 2,
              )
            )
              return;
            occupied.push({ x: local.x, y, width });
            boxes.set(svg, occupied);
            let layer = svg.querySelector<SVGGElement>('.reading-layer');
            if (!layer) {
              layer = document.createElementNS('http://www.w3.org/2000/svg', 'g');
              layer.classList.add('reading-layer');
              layer.setAttribute('pointer-events', 'none');
              svg.append(layer);
            }
            const group = document.createElementNS(svg.namespaceURI, 'g');
            const backing = document.createElementNS(svg.namespaceURI, 'rect');
            backing.setAttribute('x', String(local.x - width / 2));
            backing.setAttribute('y', String(y - 10));
            backing.setAttribute('width', String(width));
            backing.setAttribute('height', '14');
            backing.setAttribute(
              'fill',
              options.dark ? 'var(--color-score-dark-paper)' : 'var(--paper)',
            );
            const label = document.createElementNS(svg.namespaceURI, 'text');
            label.setAttribute('x', String(local.x));
            label.setAttribute('y', String(y));
            label.setAttribute('text-anchor', 'middle');
            label.setAttribute(
              'fill',
              options.dark ? 'var(--color-score-dark-cursor)' : 'var(--color-score-cursor)',
            );
            label.setAttribute('font-family', 'var(--ui)');
            label.setAttribute('font-size', '11');
            label.textContent = text;
            group.append(backing, label);
            layer.append(group);
          }),
        ),
      );
    }),
  );
}
