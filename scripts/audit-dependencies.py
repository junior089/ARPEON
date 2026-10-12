"""Audit locked Java dependencies and an optional shipped Audiveris runtime against OSV.

Build-time Python stdlib only. Sends package names/versions, never user documents.
An empty advisory response is not proof of absence of vulnerabilities.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
FALLBACK = {
    "jai-core": "javax.media:jai-core", "jama": "gov.nist.math:jama",
    "jcip-annotations": "net.jcip:jcip-annotations", "jheaps": "org.jheaps:jheaps",
    "jna": "net.java.dev.jna:jna", "org.apache.commons.io": "commons-io:commons-io",
    "jgoodies-common": "com.jgoodies:jgoodies-common",
    "jgoodies-forms": "com.jgoodies:jgoodies-forms",
    "jgoodies-looks": "com.jgoodies:jgoodies-looks",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audiveris", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    coordinates = set()
    inventory, unidentified = [], []
    for lock in ROOT.rglob("gradle.lockfile"):
        if any(part in {"build", ".tools", "node_modules"} for part in lock.parts):
            continue
        for line in lock.read_text(encoding="utf-8").splitlines():
            match = re.match(r"([^:#=]+):([^:=]+):([^=]+)=", line)
            if match:
                coordinates.add((f"{match[1]}:{match[2]}", match[3]))
    if args.audiveris:
        for jar in sorted((args.audiveris / "app").glob("*.jar")):
            packages, licenses = set(), []
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    if re.search(r"(?:^|/)(?:LICENSE|COPYING|NOTICE)(?:[./_-]|$)", name, re.I):
                        licenses.append(name)
                    if name.startswith("META-INF/maven/") and name.endswith("pom.properties"):
                        props = dict(re.findall(r"(?m)^([a-zA-Z]+)=(.+)$", archive.read(name).decode("utf-8").replace("\r", "")))
                        if {"groupId", "artifactId", "version"} <= props.keys():
                            packages.add((f"{props['groupId']}:{props['artifactId']}", props['version']))
            if not packages:
                for prefix, coordinate in FALLBACK.items():
                    match = re.fullmatch(re.escape(prefix) + r"-(.+)\.jar", jar.name)
                    if match:
                        packages.add((coordinate, match[1]))
            if not packages:
                unidentified.append(jar.name)
            coordinates.update(packages)
            with jar.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            inventory.append({"file": jar.name, "sha256": digest, "packages": sorted(packages), "embeddedNotices": licenses})
    queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version} for name, version in sorted(coordinates)]
    findings = []
    for offset in range(0, len(queries), 100):
        batch = queries[offset:offset + 100]
        request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": batch}).encode(), headers={"Content-Type": "application/json", "User-Agent": "ARPEON-dependency-audit"})
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response)["results"]
        for query, result in zip(batch, results, strict=True):
            if result.get("vulns"):
                findings.append({"query": query, "advisories": result["vulns"]})
    report = {"checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "packagesQueried": len(queries), "findings": findings, "unidentified": unidentified, "audiverisInventory": inventory}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Queried {len(queries)} Maven packages: {len(findings)} affected packages; {len(unidentified)} jars without Maven identity.")
    raise SystemExit(1 if findings else 0)


if __name__ == "__main__":
    main()
