# Third-party notices

ARPEON code and original fixtures are licensed under AGPL-3.0-or-later. Third-party
components retain their licenses; they are not relicensed by this repository.

| Component | Version | License | Use / source |
| --- | --- | --- | --- |
| Angular | 22.2.1 | MIT | UI; https://github.com/angular/angular |
| Angular CLI / build | 22.2.2 | MIT | Development; https://github.com/angular/angular-cli |
| RxJS | 7.8.2 | Apache-2.0 | Angular runtime; https://github.com/ReactiveX/rxjs |
| tslib | 2.8.1 | 0BSD | TypeScript runtime; https://github.com/microsoft/tslib |
| Gson | 2.14.0 | Apache-2.0 | Local JSON bridge; https://github.com/google/gson |
| OpenSheetMusicDisplay | 2.2.0 | BSD-3-Clause | Score SVG; https://github.com/opensheetmusicdisplay/opensheetmusicdisplay |
| VexFlow | 1.2.93 | MIT | OSMD notation engine; https://github.com/0xfe/vexflow |
| JSZip | 3.10.1 | MIT option | OSMD transitive; https://github.com/Stuk/jszip |
| pako | 1.0.11 | MIT AND Zlib | OSMD transitive; https://github.com/nodeca/pako |
| loglevel | 1.9.2 | MIT | OSMD transitive; https://github.com/pimterry/loglevel |
| typescript-collections | 1.3.3 | MIT | OSMD transitive; https://github.com/basarat/typescript-collections |
| Error Prone annotations | 2.48.0 | Apache-2.0 | Gson transitive runtime metadata; https://github.com/google/error-prone |
| Inter Variable | 5.3.0 package | OFL-1.1 | UI font; https://github.com/rsms/inter |
| JetBrains Mono Variable | 5.3.0 package | OFL-1.1 | Measurements; https://github.com/JetBrains/JetBrainsMono |
| Gradle / wrapper | 9.8.1 | Apache-2.0 | Build; https://github.com/gradle/gradle |
| JUnit | 5.14.1 | EPL-2.0 | Tests only; https://github.com/junit-team/junit-framework |
| google-java-format | 1.37.0 | Apache-2.0 | Java formatter; https://github.com/google/google-java-format |
| Flute fingering chart | 2014 file | CC0 1.0 | Offline reference image; https://commons.wikimedia.org/wiki/File:Flute_fingering_chart.jpg |

Frontend development dependencies and their transitive licenses are recorded in
[npm-dependencies.json](docs/legal/npm-dependencies.json), generated from the installed
lockfile by `node scripts/check-licenses.mjs --write`. Re-run the check after installing
with `npm ci`. This is a license allowlist check, not legal advice or a vulnerability scan.

The Angular build's `3rdpartylicenses.txt`, font licenses, Apache license and these notices
are included in the desktop's web resources. Fonts are distributed unmodified, with their
copyright and OFL notices in [docs/legal/licenses](docs/legal/licenses). Gradle's generated
wrapper retains upstream copyright headers. Tooling is separate from the application runtime.
JDK and Node distributions are developer prerequisites, not bundled in the desktop archive.

YIN is implemented from its published mathematical description, without copied source:
Alain de Cheveigné and Hideki Kawahara, “YIN, a fundamental frequency estimator for speech
and music,” JASA 111(4), 2002, https://doi.org/10.1121/1.1458024.
The fixture melody and synthetic test signal recipes were created for this repository;
no commercial score, recording, or pretrained model is included. The flute fingering chart is
bundled unmodified under CC0 1.0. Its original author is listed as unknown on Wikimedia Commons;
SHA-1: `07f5b244b4f6148aaad5b05501761005cf5d6a5f`. Yamaha's official flute fingering
guide is a separate reference; no alternate-fingering dataset is inferred from either chart.
