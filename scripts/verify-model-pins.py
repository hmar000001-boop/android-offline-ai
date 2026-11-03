#!/usr/bin/env python3
"""Validate the pinned model catalog and optionally download/check artifacts.

Default: validate every catalog entry's HTTPS host, SHA-256 syntax and size bounds.
--download: stream each selected artifact and compare its actual SHA-256 and byte size.
--only ID: limit the operation to a single exact model id.
--output-dir DIR: after successful download verification, atomically save the artifact there.
"""
import argparse
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
    id_constants = dict(re.findall(r'const val (\w+) = "([^"]+)"', text))
    for block in re.findall(r"ApprovedModelArtifact\((.*?)\n    \)", text, re.S):
        def field(name, quoted=True):
            pattern = rf'{name}\s*=\s*"([^"]*)"' if quoted else rf"{name}\s*=\s*([0-9_]+)L"
            match = re.search(pattern, block)
            return match.group(1) if match else None
        id_match = re.search(r"id\s*=\s*ModelIds\.(\w+)", block)
        artifacts.append({
            "id": field("id") or (id_constants.get(id_match.group(1)) if id_match else None),
            "name": field("displayName"),
            "file_name": field("fileName"),
            "url": field("downloadUrl"),
            "sha256": field("sha256"),
            "min": int(field("minimumBytes", False)),
            "max": int(field("maximumBytes", False)),
        })
    return artifacts


def host_ok(host):
    host = host.lower().rstrip(".")
    return host in APPROVED_HOSTS or host.endswith(".huggingface.co") or host.endswith(".hf.co")


def download_and_verify(item, output_dir):
    digest = hashlib.sha256()
    size = 0
    temporary = None
    output = None
    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)
        output_path = output_dir / item["file_name"]
        temporary = output_path.with_name(output_path.name + ".part")
        temporary.unlink(missing_ok=True)
        output = temporary.open("wb")
    try:
        request = urllib.request.Request(item["url"], headers={"User-Agent": "ScreenVeil-pin-check"})
        with urllib.request.urlopen(request, timeout=120) as response:
            final_host = urlparse(response.geturl()).hostname or ""
            if not host_ok(final_host):
                raise ValueError(f"redirected to an unapproved host: {final_host}")
            for chunk in iter(lambda: response.read(1 << 20), b""):
                size += len(chunk)
                if size > item["max"]:
                    raise ValueError(f"download exceeded maximum allowed size {item['max']} bytes")
                digest.update(chunk)
                if output is not None:
                    output.write(chunk)
        if not item["min"] <= size <= item["max"]:
            raise ValueError(f"size {size} outside bounds {item['min']}..{item['max']}")
        actual = digest.hexdigest()
        if actual != item["sha256"]:
            raise ValueError(f"SHA-256 MISMATCH: actual {actual}")
        if output is not None:
            output.flush()
            output.close()
            output = None
            temporary.replace(output_dir / item["file_name"])
        return size
    finally:
        if output is not None:
            output.close()
        if temporary is not None and temporary.exists():
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="download and verify selected model artifacts")
    parser.add_argument("--only", metavar="MODEL_ID", help="verify only one exact catalog model id")
    parser.add_argument("--output-dir", type=Path, help="save successfully verified downloads in this directory")
    args = parser.parse_args()
    if args.output_dir is not None and not args.download:
        parser.error("--output-dir requires --download")

    artifacts = parse(CATALOG.read_text(encoding="utf-8"))
    if len(artifacts) < 5:
        sys.exit(f"Expected at least 5 catalog entries, parsed {len(artifacts)}")
    if args.only:
        artifacts = [item for item in artifacts if item["id"] == args.only]
        if not artifacts:
            sys.exit(f"Model id not found in catalog: {args.only}")

    failures = 0
    for item in artifacts:
        problems = []
        url = urlparse(item["url"] or "")
        if url.scheme != "https" or not host_ok(url.hostname or ""):
            problems.append(f"URL host not approved: {item['url']}")
        if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"] or ""):
            problems.append("sha256 is not 64 lowercase hex characters")
        if not item["file_name"] or Path(item["file_name"]).name != item["file_name"]:
            problems.append("fileName must be a simple file name")
        if not 0 < item["min"] <= item["max"]:
            problems.append("invalid size bounds")
        if args.download and not problems:
            try:
                size = download_and_verify(item, args.output_dir)
                print(f"       verified bytes={size}")
            except Exception as error:
                problems.append(f"download/verification failed: {error}")
        status = "FAIL" if problems else "ok"
        failures += bool(problems)
        print(f"[{status}] {item['name']} ({item['id']})")
        for problem in problems:
            print(f"       {problem}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
