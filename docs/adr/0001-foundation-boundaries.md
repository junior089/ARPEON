# 0001 — Milestone 0 boundaries

Status: accepted for the foundation. Scope: Milestone 0 only.

Use a Gradle monorepo, Java 25, JUnit 5 and a strict Angular frontend. Create only domain,
MusicXML, audio, DSP, tuner and desktop modules. The desktop composes concrete adapters;
audio input and pitch detection interfaces correspond to hardware and algorithm test seams.

Java Sound is the initial capture adapter because it ships with the JDK and avoids a native
dependency before hardware evidence justifies one. Capture uses signed 16-bit mono PCM at
48 kHz or 44.1 kHz, discovered for each mixer. Only mono is offered in this milestone;
multichannel mapping is not guessed. A bounded two-frame queue isolates capture from DSP.

Do not introduce storage, score rendering, transport clocks, editing, OMR, ML, a plugin
API or unused modules. The MusicXML importer is exercised by a real, original fixture and
rejects unsupported semantics instead of returning an inaccurate score.

Current [Angular compatibility](https://angular.dev/reference/versions) and
[Gradle Java compatibility](https://docs.gradle.org/9.8.1/userguide/compatibility.html)
were checked when selecting pinned versions. Angular 22 uses TypeScript 6.0 and Node 24;
Java 25 is enforced by Gradle toolchains. JUnit remains on 5.x as required by the project.
