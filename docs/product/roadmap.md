# Roadmap addendum

The attached ARPEON specification defines the milestone order and remains authoritative.
This addendum makes acceptance criteria explicit where that plan leaves room for interpretation.
It does not change milestone order. Milestones 2 and 3 are implemented as described in the
README and beginner-mode document, with remaining hardware verification. Milestone 4 now
implements the bounded local import subset in [import capabilities](import-capabilities.md);
Milestone 5 has one approved, bounded Commons MIDI adapter and a policy registry. Milestones
6–8 have local OMR, post-run analysis and live score following. Milestone 9 evaluates an
experimental model against the deterministic follower without enabling it in the app.

## Milestone 2 — Editing and Transposition

Alongside the specified editing, transposition and MusicXML export work:

- Treat transverse flute as the first fully supported instrument profile. Define its
  written/sounding and comfortable ranges, clef, transposition and octave adaptation from
  verified references. Show out-of-range and adaptation warnings before changing a score;
  preserve the original pitch and let the user review the proposed result.
- Add an audible, selectable flute playback voice using an available synthesizer preset or
  distributable licensed samples, with attribution and an explicit fallback if unavailable.
  Verify audible output on real hardware; a passing MIDI-event test alone does not establish
  sound quality or device support.
- Keep voice selection and musical limits in instrument profiles, not flute-specific branches
  in the score or transport. Other instruments can then supply their own range,
  transposition and playback voice without changing those shared algorithms.
- Cover written/sounding pitch, range boundaries, octave preview and instrument voice
  selection with regression tests.

## Milestone 3 — Beginner Practice

Keep the specified flute fingering reference, note and long-tone trainers, scales, arpeggios,
rhythm basics, loops, progressive tempo and session history here. Connect fingering, reference
notes and exercises to the verified flute profile. Mark alternative fingerings and unavailable
reference data clearly. Other instruments use separate licensed/verified datasets as they are
added; no fingering chart is inferred from another instrument.

## Milestone 4 — Import Expansion

Keep MIDI, textual notes, solfège, melodic cipher, ABC if justified, paste and monophonic
audio-to-draft in this milestone. Normalize permitted input into source-aware note/rhythm
events so that later web adapters can reuse the same parsers and analysis. Detect format early,
reject unsupported or ambiguous input with a reason, retain provenance and uncertainty, and
never synthesize exact melody or rhythm absent from the source. Test representative valid,
ambiguous and hostile inputs. Measure parsing time and memory on declared fixture sizes before
claiming that bulk import is fast.

## Milestone 5 — Web Source Adapters

Keep provider-specific and generic web import here, subject to the access policy in the
specification and [source import policy](../legal/source-import-policy.md). A generic path is
only for sources that permit automation; it is not an unrestricted site scraper.

- Maintain a provider registry with reviewed access mode, authentication, permitted content,
  licensing, rate limits, terms-review date and implementation status. Start with at least one
  source that demonstrably permits the intended integration; introduce a shared adapter
  interface only when that concrete adapter needs it.
- For a user-requested URL, check provider eligibility before fetching. Fetch only the
  requested resource with bounded size, time, redirects and concurrency; validate response
  content type before parsing and never crawl recursively. Extract supported score/text data,
  pass it to Milestone 4 parsers, and show a preview with source, known or unknown rights
  status, confidence and any missing melody/rhythm before acceptance.
- Preserve source URL, provider, retrieval time and transformation history. Deduplicate within
  an import session and cache only where the provider permits it. Fail visibly when a site
  changes format or policy rather than guessing musical data.
- Use deterministic provider fixtures and compliance tests for limits, failures, policy
  changes and unauthorized access. Benchmark filtering, fetch and parsing separately on
  declared inputs, reporting measured latency and memory rather than promising speed.
- MuseScore.com remains manual/user-provided export, and Cifra Melódica remains manual/paste,
  unless current official integration or explicit authorization permits the specific automated
  access. Provide those manual flows through the supported import formats. Apply the same
  per-provider review to any additional site before adding its adapter.

Milestone 6 now has a bounded Audiveris CLI adapter and explicit PDF/image review path.
The executable is installed separately; the narrow MusicXML subset rejects unsupported
musical structures. Milestones 7–9 retain the order and scope in the attached specification.

Milestone 7 adds fixed-tempo, end-of-attempt monophonic comparison for a selected score part.
It segments timestamped stable pitch windows, aligns expected and observed events, and reports
separate pitch, timing, rhythm, intonation, missed/extra and confidence measures. Formulas,
thresholds and missing-data behavior are in [scoring](../analysis/scoring.md). Score following
and recovery enter Milestone 8 as the bounded note-sequence follower described in
[following](../analysis/following.md). Milestone 9 evaluates one synthetic transition model
against that deterministic baseline; the [benchmark](../analysis/ml-evaluation.md) rejects
product integration because false jumps increase and real-performance validation is absent.
