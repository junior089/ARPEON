# Native distribution review

The ARPEON source is AGPL-3.0-or-later. This does **not** make every binary in
the Windows package freely redistributable. Public distribution is blocked by
`packaging/release-readiness.json`; current packages are local engineering builds.

## Audiveris

Audiveris 5.11.0 is distributed as a separate CLI process with its own Java
runtime. ARPEON neither incorporates its classes into the domain nor modifies
`audiveris.jar`. The official MSI is administratively extracted **during build**.
No MSI, package download, separate Java installation or Audiveris setup runs
on the end user's machine. The upstream source ZIP and AGPL license accompany
the runtime. Source/version/hash provenance is in `packaging/native-lock.json`.

Its official distribution includes `javax.media:jai-core:1.1.3`, a proprietary
Sun Java Advanced Imaging binary. The [original license](licenses/jai-1.1.3.txt)
contains supplemental redistribution conditions, requirements for a protective
end-user license, and an indemnification clause. This is a material open-source
distribution issue; reproducing the license does not resolve compatibility or
establish permission. See the [upstream license](https://download.java.net/media/jai/builds/release/1_1_3/LICENSE-jai.txt)
and Audiveris' `app/build.gradle`, retained in its source ZIP.

Two possible release paths remain: review and fulfill all redistribution
conditions, or maintain and test an Audiveris variant that replaces JAI with a
free implementation. The second requires an upstream patch and a wider OMR
corpus. Neither decision has been silently made or claimed complete.

The build makes documented security-only dependency substitutions:

| Component | Upstream | Packaged |
| --- | --- | --- |
| iText | 5.5.13.2 | 5.5.13.6 |
| Jackson Core / Databind | 2.20.0 | 2.22.3 |
| Jackson Annotations | 2.20 | 2.22 |
| Logback Core / Classic | 1.4.14 | 1.5.38 |
| Duplicate Commons IO | 2.4 | removed; upstream's earlier classpath entry 2.16.1 remains |

Replacement JARs, POMs and source JARs are checksum-pinned. The launcher classpath
is updated and OMR heap is capped at 2 GiB. PDF/PNG recognition and native
cancellation were exercised after these changes. This is an ARPEON runtime
assembly of Audiveris 5.11.0, not a claim that the official MSI contains the fixes.
iText retains its AGPL obligations; Jackson is Apache-2.0; Logback retains its
EPL/LGPL alternatives. Text OCR uses unmodified `eng`/`por` trained data from
the pinned Tesseract tessdata commit, including its Apache-2.0 license. Audiveris
uses the legacy OCR engine, so LSTM-only `tessdata_fast` models are unsuitable.

`scripts/audit-dependencies.py` inventories JAR coordinates, hashes and embedded
notices and queries OSV. A JAR missing a notice is not automatically unlicensed;
the complete third-party notice/source review remains a release gate. Native
libraries, OCR data and vendored Chromium code are not fully covered by Maven
or npm advisory databases.

## Other native components

JCEF/JCEF Maven and Chromium retain their upstream license files in `tools/cef`.
Chromium includes third-party components with separate terms. WinSparkle 0.9.4
retains `COPYING`, `COPYING.expat` and `AUTHORS` in `tools/updater`. WiX 3.14.1
is a build tool, not a runtime component. Its MS-RL terms remain with the cached
tool distribution.

Both trimmed ARPEON Java and Audiveris' Java retain `runtime/legal`. Before a
public release, archive the exact corresponding OpenJDK/Temurin sources and
build provenance for both runtimes, validate their source-distribution obligations
and complete the native notice inventory. A generic link to the newest JDK
release is insufficient evidence that these obligations have been met.
