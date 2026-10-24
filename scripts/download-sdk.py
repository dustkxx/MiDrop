#!/usr/bin/env python3
"""Download project-local official Android SDK packages with process-local routing.

Direct: python3 scripts/download-sdk.py --google-ip <current resolved IPv4>
Proxy:  python3 scripts/download-sdk.py --proxy http://127.0.0.1:7890
Neither mode changes macOS settings or other processes' environment.
"""
import argparse
import hashlib
import ipaddress
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    route = parser.add_mutually_exclusive_group()
    route.add_argument("--proxy", help="Only this process uses the specified HTTP/SOCKS proxy")
    route.add_argument("--google-ip", help="Use a resolved IPv4 for dl.google.com and bypass all proxies")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    cache = root / ".tools/downloads"
    sdk = root / ".sdk"
    cache.mkdir(parents=True, exist_ok=True)
    sdk.mkdir(parents=True, exist_ok=True)
    command = ["curl", "-fL", "--connect-timeout", "15", "--max-time", "600", "--retry", "2"]
    if args.proxy:
        command += ["--proxy", args.proxy, "--noproxy", ""]
    else:
        command += ["--noproxy", "*"]
    if args.google_ip:
        ipaddress.IPv4Address(args.google_ip)
        command += ["--resolve", "dl.google.com:443:" + args.google_ip]

    def download(name, destination):
        subprocess.run(command + ["https://dl.google.com/android/repository/" + name,
                                  "-o", str(destination)], check=True)

    metadata = cache / "repository.xml"
    download("repository2-1.xml", metadata)
    packages = ET.parse(metadata).getroot().findall("remotePackage")
    desired = [
        ("platforms;android-35", sdk / "platforms/android-35"),
        ("build-tools;35.0.0", sdk / "build-tools/35.0.0"),
        ("platform-tools", sdk / "platform-tools"),
    ]
    for package, target in desired:
        if (target / "source.properties").exists():
            print("Already installed:", target, flush=True)
            continue
        selected = None
        for item in packages:
            if item.get("path") != package or item.find("obsolete") is not None:
                continue
            for archive in item.findall("./archives/archive"):
                if archive.findtext("host-os") not in (None, "macosx"):
                    continue
                complete = archive.find("complete")
                if complete is None:
                    continue
                name = complete.findtext("url")
                # Select the standard API platform, not an SDK extension variant.
                if package == "platforms;android-35" and not name.startswith("platform-35_r"):
                    continue
                selected = (name, int(complete.findtext("size")), complete.findtext("checksum"))
                break
            if selected:
                break
        if not selected:
            raise RuntimeError("Official archive not found: " + package)
        name, size, checksum = selected
        archive = cache / name

        def valid():
            return (archive.exists() and archive.stat().st_size == size
                    and hashlib.sha1(archive.read_bytes()).hexdigest() == checksum)

        if not valid():
            temporary = archive.with_suffix(".download")
            download(name, temporary)
            temporary.replace(archive)
        if not valid():
            raise RuntimeError("Official checksum mismatch: " + name)
        print("Checksum verified:", name, flush=True)
        with tempfile.TemporaryDirectory(prefix=".unpack-", dir=sdk) as staging:
            subprocess.run(["unzip", "-q", "-o", str(archive), "-d", staging], check=True)
            folders = [p for p in Path(staging).iterdir() if p.is_dir() and p.name != "__MACOSX"]
            if len(folders) != 1:
                raise RuntimeError("Unexpected SDK archive layout: " + name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                raise RuntimeError("Incomplete SDK directory exists; inspect it first: " + str(target))
            shutil.move(str(folders[0]), target)
        print("Installed:", target, flush=True)
    print("Project-local SDK ready:", sdk, flush=True)


if __name__ == "__main__":
    main()
