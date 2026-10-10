# ARPEON — workstation UX/UI

## Audit and scope

The redesign was implemented over the existing, locally modified Angular application. No Java
algorithms, transport contracts, import limits, microphone consent, licensing or domain models
were changed by this work. No frontend dependency was added. Local Inter and JetBrains Mono
assets were retained to avoid network fonts and preserve offline operation.

The original interface used a tall masthead, floating playback card, horizontal numbered
navigation, large introductory headings and a long document page. Editing was below notation
and fingering rather than beside the selection. Import methods competed in a grid of cards.
Most controls required scrolling away from the score.

The app has no Angular router: the root switches five standalone workspaces and provides the
shared `ScoreSession`. Import/practice/tuner/analysis services retain their existing lifecycles.
Switching away still invokes cleanup of capture and polling through the existing components.

| Area | Operational capabilities preserved |
| --- | --- |
| Score | MusicXML/MXL/MSCZ, local projects, part selection, mute/solo, instrument, note/chord/rest editing, duration, ties, transposition previews, undo/redo and export |
| Transport | Java MIDI play/pause/stop, seek, starting BPM, metronome and count-in |
| Practice | Generated flute exercises, reference playback, repeat/progressive tempo, note selection, fingering, microphone feedback and local history |
| Tuner | Device selection, reference A, note/frequency/cents/confidence and capture diagnostics |
| Import | Pasted notation, bounded MIDI/WAV, public Wikimedia MIDI, local Audiveris setup/OMR and explicit review/acceptance |
| Analysis | Monophonic following, timed attempts, independent metrics and revision-specific reports |

Limitations remain explicit: OMR has no per-note confidence, live following is monophonic,
recognition needs review, and learned-model experiments are not exposed as production features.
There is no invented note palette, multi-document tab system, score loop API or generic settings
screen. Exercise looping remains in the real practice controls.

## Workspace architecture

- A 40px menu strip groups existing commands under Arquivo, Editar, Exibir, Partitura,
  Reprodução, Ferramentas and Ajuda. Native disclosure menus support arrows, Home/End,
  Escape, outside click and normal Tab navigation. Help uses a native modal dialog.
- A 68px activity rail (56px in compact windows) switches the five workspaces. Labels supplement
  monochrome, original SVG line icons. The active area has a side marker and text contrast.
- The score workspace uses a 208px explorer, flexible document canvas and 280–460px inspector.
  The score stays light by default; the existing optional dark score remains available.
- Projects and instrument parts share the explorer. Editing and transposition occupy the
  inspector; notes/fingering are a collapsible lower section. Selection on notation no longer
  scrolls the user away from the score. Enter on notation opens the keyboard note list.
- Inspector resizing supports pointer capture and a focusable separator. Arrow keys adjust it
  in 16px steps; Home/End choose its bounds. ResizeObserver reflows notation after panel changes.
- Real OSMD zoom spans 50–200%. Preferences are validated, bounded and stored under
  `arpeon.layout.v1`; blocked/corrupt storage falls back safely. Exibir restores defaults.
- At widths up to 980px, side panels fold to prioritize the score. Their toolbar buttons reopen
  scrollable overlays; returning to a wide window restores the previous wide layout. At widths up to 600px, notation keeps a readable minimum width and scrolls horizontally within the canvas.
- Transport is docked above a small status bar. Its document name identifies the score transport
  separately from exercise playback. It remains reachable when the document scrolls.
- Import uses a source selector and three explicit stages: source, review, editor. Switching
  source panels does not discard their input. Assumptions must still be acknowledged.
- Practice places the generated notation beside its setup, inside the execution area. Tuner
  separates the measurement display from the device inspector. Analysis keeps preparation,
  execution and reports distinct.

## Visual system

Tokens live in `frontend/src/styles/styles.scss`. Feature styles reference semantic colors.

| Role | Token / value |
| --- | --- |
| Canvas / panel / raised | `--background` #0D0F12 / `--surface` #17191D / `--raised` #202329 |
| Structural separator | `--border` #30343B |
| Form boundary | `--control-border` #656F7E |
| Primary / secondary text | #E6E8EC / #A4ADB9 |
| Selected text / action fill | `--accent` #78B5D5 / `--accent-fill` #478FB5 |
| Selection surface | #233B49 |
| Error / success / warning | #F39595 / #8AC5A0 / #DBB776 |
| Spacing | 4, 8, 12, 16, 24, 32px |
| Controls / corners | 32px / 3px; 24–28px for secondary compact controls |
| Type | 12px UI, 11px metadata, 18px workspace title; tabular monospace for musical measurements |
| Focus | 2px accent outline, 2px offset |

Hover and pressed states change surfaces without movement. Selection also uses borders or markers.
Disabled actions remain labeled. Busy operations announce status; errors retain role=alert.
The only shadow is the menu's overlay separation. No gradients, blur, glow or external font calls
were added. Reduced-motion preference removes transitions. Native destructive-action confirmations
remain in place, including dirty-document replacement, project deletion and application shutdown.
The favicon uses a local JPEG because the existing Java static-resource allowlist supports JPEG.

## Keyboard commands

| Action | Windows/Linux | macOS |
| --- | --- | --- |
| Open / save | Ctrl O / Ctrl S | Cmd O / Cmd S |
| Undo / redo (editor) | Ctrl Z / Ctrl Shift Z, Ctrl Y | Cmd Z / Cmd Shift Z |
| Play/pause / stop score | Space / Shift Space | Space / Shift Space |
| Switch workspace | Alt 1–5 | Option 1–5 |
| Explorer / inspector | Ctrl B / Ctrl Alt B | Cmd B / Cmd Option B |
| Help | F1 | F1 |

Global shortcuts do not intercept editable fields, held-key repeats or the help dialog.
Space on buttons, links and disclosure summaries keeps its native activation behavior.

## Validation

Validated with the bundled Node 24 runtime and a Java DesktopServer using an isolated local
profile under `.tools/redesign-profile`. Production UI and real backend were used: no mocked
requests or simulated scores in browser checks.

- Angular production build; strict application/spec TypeScript checks; ESLint and Prettier.
- Full Gradle build (compile, tests, distributions) passed with `-x checkJavaFormat`. The plain
  build stopped on formatting in the already-modified `MidiScorePlayer.java` in
  `modules/arpeon-playback`; this redesign did not edit that file.
- 28 frontend tests, including bounded/corrupt layout preferences, keyboard safeguards,
  menu navigation, inspector bounds and correct initial select values.
- Browser: MusicXML open and rendering; pitch edit; undo/redo shortcuts; save; reload;
  exported MusicXML; play/pause/stop; BPM; metronome; mute; zoom; inspector persistence.
- Browser: pasted-note review gated by acknowledgment, accepted draft rendering, generated
  practice notation, tuner idle/device controls and analysis workspace.
- Responsive checks: 1440, 1280, 1024, 800, 600 and 390px widths. Capture files are stored locally
  under `.tools/redesign-*.png` (ignored build/verification artifacts).

Hardware microphone accuracy, audible output quality, long live-follow sessions, Audiveris
recognition and macOS/Linux native behavior require their respective environments. This UI work
does not claim those were revalidated. The initial empty practice endpoint returns its existing
409 before an exercise exists; the UI handles it. The final loaded browser session has no console
errors. No before screenshot was retained: the first static preview could not be reached before
its bundle was rebuilt.

## Design references

Principles studied, with original ARPEON implementation and icons:

- [Dorico: Write mode zones](https://www.steinberg.help/r/dorico-pro/6.1/en/dorico/topics/write_mode/write_mode_window_r.html): document priority and contextual panels.
- [MuseScore Studio: workspaces](https://handbook.musescore.org/en_gb/customization/workspaces): coherent arrangement of toolbars and panels.
- [Ableton: Live concepts](https://www.ableton.com/en/manual/live-concepts/): transport and context separated from document work.
- [Logic Pro: main window](https://support.apple.com/en-my/guide/logicpro/lgcp2a07a994/10.7/mac/11.0): integrated working regions.
- [VS Code: interface](https://code.visualstudio.com/docs/editing/getting-started/userinterface): compact navigation, secondary sidebar and status bar.
- [Figma: properties sidebar](https://help.figma.com/hc/en-us/articles/360039832014-Design-prototype-and-explore-layer-properties-in-the-right-sidebar): selection-oriented properties.
- [Blender: regions](https://docs.blender.org/manual/id/5.1/interface/window_system/regions.html): bounded, contextual editors.



