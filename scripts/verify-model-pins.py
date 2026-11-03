#!/usr/bin/env python3
"""Cross-check the model catalog pins.

Without --download: parse GemmaModelCatalog.kt and validate every artifact entry (HTTPS URL,
64-hex SHA-256, size bounds, approved host). With --download: also fetch each artifact and compare
the real SHA-256 and size, so a re-pointed upstream release or a mistyped pin is caught in CI
instead of on a user's phone.
"""
import hashlib
import re
import sys
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/java/dev/screenveil/app/model/GemmaModelCatalog.kt"
APPROVED_HOSTS = {
    "storage.googleapis.com", "huggingface.co", "hf.co", "github.com",
    "objects.githubusercontent.com", "release-assets.githubusercontent.com",
}


def parse(text):
    artifacts = []
    for block in re.findall(r"ApprovedModelArtifact\((.*?)\n    \)", text, re.S):
        def field(name, quoted=True):
            pattern = rf'{name}\s*=\s*"([^"]*)"' if quoted else rf"{name}\s*=\s*([0-9_]+)L"
            match = re.search(pattern, block)
            return match.group(1) if match else None
        artifacts.append({
            "name": field("displayName"),
            "url": field("downloadUrl"),
            "sha256": field("sha256"),
            "min": int(field("minimumBytes", False).replace("_", "")),
            "max": int(field("maximumBytes", False).replace("_", "")),
        })
    return artifacts


def host_ok(host):
    host = host.lower().rstrip(".")
    return host in APPROVED_HOSTS or host.endswith(".huggingface.co") or host.endswith(".hf.co")


def main():
    download = "--download" in sys.argv
    artifacts = parse(CATALOG.read_text())
    if len(artifacts) < 4:
        sys.exit(f"Expected at least 4 catalog entries, parsed {len(artifacts)}")
    failures = 0
    for item in artifacts:
        problems = []
        url = urlparse(item["url"])
        if url.scheme != "https" or not host_ok(url.hostname or ""):
            problems.append(f"URL host not approved: {item['url']}")
        if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"] or ""):
            problems.append("sha256 is not 64 lowercase hex characters")
        if not 0 < item["min"] <= item["max"]:
            problems.append("invalid size bounds")
        if download and not problems:
            digest, size = hashlib.sha256(), 0
            request = urllib.request.Request(item["url"], headers={"User-Agent": "ScreenVeil-pin-check"})
            with urllib.request.urlopen(request, timeout=120) as response:
                for chunk in iter(lambda: response.read(1 << 20), b""):
                    digest.update(chunk)
                    size += len(chunk)
            if digest.hexdigest() != item["sha256"]:
                problems.append(f"SHA-256 MISMATCH: actual {digest.hexdigest()}")
            if not item["min"] <= size <= item["max"]:
                problems.append(f"size {size} outside bounds")
        status = "FAIL" if problems else "ok"
        failures += bool(problems)
        print(f"[{status}] {item['name']}")
        for problem in problems:
            print(f"       {problem}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
