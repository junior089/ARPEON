# Dependency policy

Before adding dependencies or assets, identify exact version, source, SPDX license,
attribution/redistribution obligations, maintenance, security history, transitives and the
JDK/platform alternative. Pin direct versions, generate lockfiles using real resolvers and
retain notices. Do not use `latest` in build configuration or hand-edit dependency resolution.

Runtime choices: Angular (required UI framework), RxJS/tslib (framework support),
Gson (small Java JSON boundary), Inter/JetBrains Mono (specified fonts), and
OpenSheetMusicDisplay 2.2.0 for MusicXML SVG rendering. Java Sound, XML, MIDI and HTTP use
the JDK. PDFBox 3.0.8 (Apache-2.0) validates PDF page structure before the external
Audiveris CLI receives input. Audiveris 5.11.0 is a separate AGPL-3.0 program, not bundled in
ARPEON. On 64-bit Windows, the user can explicitly request its official MSI download; ARPEON
checks the published SHA-256 and extracts it into the user's local data folder. No native audio,
database or ML dependency is added.

OpenSheetMusicDisplay is BSD-3-Clause. Its bundled VexFlow, loglevel and
typescript-collections use MIT; JSZip is dual MIT/GPL-3.0-or-later and ARPEON chooses MIT;
pako includes MIT and Zlib notices. Optional platform packages `expand-template` and `rc`
have permissive license alternatives and are not present in this Windows installation.
Their exact locked licenses remain inventoried. The required copyright and license texts
are retained in `docs/legal/licenses` and the desktop distribution.

MIT, BSD, ISC, Apache-2.0, 0BSD and CC0 tooling dependencies have permissive terms; retain
applicable notices. OFL fonts are distributed unmodified, with complete licenses, and are
not sold separately. JUnit EPL-2.0 is test-only, not packaged into the application. Gradle and
the formatter are build tools. JDK 25 and Node 24 are build prerequisites. The portable Windows
package embeds a trimmed Eclipse Temurin 25.0.4.1+1 runtime with its `runtime/legal` texts;
Node is not shipped. The Gradle ZIP/TAR distribution still requires a local JDK 25.

`scripts/check-licenses.mjs` checks installed npm metadata against a reviewed allowlist and
the committed inventory. It fails on missing/unreviewed licenses. This is not a definitive
legal assessment. Ambiguous additions stop pending review. Security scanning and legal
compatibility are different checks; never present either as universal assurance.

Sources: upstream LICENSE files and package metadata, retained under `docs/legal/licenses`
for redistributed assets. The distribution includes Angular's generated third-party notices.
An AGPL corresponding-source archive is accessible through the running application.

Transitive build-only exceptions reviewed from shipped licenses: Lightning CSS is MPL-2.0
(unmodified CSS build tool; its implementation is not bundled into the app), and caniuse-lite
is CC-BY-4.0 (build-time compatibility data; not a runtime dataset). Preserve upstream source
links and notices when redistributing those tools. The checker permits these licenses only
when the lockfile marks the package as development-only. Buf's Apache-2.0 AND BSD-3-Clause
terms require both notices; MIT-0 is permissive. See the locked inventory for versions.
