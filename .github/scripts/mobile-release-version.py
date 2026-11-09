#!/usr/bin/env python3
"""Choose and verify Android release codes against the latest published fork APK."""

import argparse
from dataclasses import dataclass
import json
import re
import subprocess
import sys

PACKAGE = "com.nuvioreshaped.app"
MAX_CODE = 2_100_000_000


def next_code(official, previous=0):
    if not 0 < official <= MAX_CODE or not 0 <= previous < MAX_CODE:
        raise ValueError("Invalid or exhausted Android versionCode")
    return max(official, previous + 1)


def previous_release(releases):
    candidates = [
        release for release in releases
        if not release.get("draft") and release.get("published_at") and release.get("tag_name")
        and any(re.fullmatch(r"NuvioRS-.+-arm64[.]apk", asset.get("name", ""))
                for asset in release.get("assets", []))
    ]
    return max(candidates, key=lambda release: release["published_at"])["tag_name"] if candidates else ""


def package_info(badging):
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != PACKAGE:
        raise ValueError("Expected the Nuvio Reshaped mobile package")
    return int(match[2]), match[3]


@dataclass(frozen=True)
class Apk:
    code: int
    version: str
    signers: frozenset


def inspect(apk, aapt, apksigner):
    code, version = package_info(subprocess.check_output([aapt, "dump", "badging", apk], text=True))
    signing = subprocess.check_output([apksigner, "verify", "--print-certs", apk], text=True)
    # Build Tools 37 labels signing schemes ("V2 Signer:"); older versions
    # label signer indexes ("Signer #1"). Source-stamp identities are separate.
    signers = frozenset(value.lower() for value in re.findall(
        r"^(?:Signer #[0-9]+|V[0-9.]+ Signer(?: #[0-9]+)?):? certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        signing, re.M,
    ))
    if not signers:
        raise ValueError("No verified APK signing identity")
    return Apk(code, version, signers)


def verify(apks, previous, expected_code, expected_version):
    if not apks:
        raise ValueError("No release APKs")
    identity = previous.signers if previous else apks[0].signers
    for apk in apks:
        if apk.signers != identity or not identity:
            raise ValueError("Release APK signing identity changed")
        if apk.code != expected_code or apk.version != expected_version:
            raise ValueError("Release APK version does not match the selected release")
        if previous and apk.code <= previous.code:
            raise ValueError("Android versionCode must increase over the previous release")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("previous-release")
    commands.add_parser("apk-code")
    choose = commands.add_parser("next-code")
    choose.add_argument("official", type=int)
    choose.add_argument("previous", type=int, nargs="?", default=0)
    check = commands.add_parser("verify")
    check.add_argument("--aapt", required=True)
    check.add_argument("--apksigner", required=True)
    check.add_argument("--expected-code", type=int, required=True)
    check.add_argument("--expected-version", required=True)
    check.add_argument("--previous-apk")
    check.add_argument("apks", nargs="+")
    args = parser.parse_args()
    try:
        if args.command == "previous-release":
            print(previous_release([json.loads(line) for line in sys.stdin if line.strip()]))
        elif args.command == "apk-code":
            print(package_info(sys.stdin.read())[0])
        elif args.command == "next-code":
            print(next_code(args.official, args.previous))
        else:
            previous = inspect(args.previous_apk, args.aapt, args.apksigner) if args.previous_apk else None
            apks = [inspect(path, args.aapt, args.apksigner) for path in args.apks]
            verify(apks, previous, args.expected_code, args.expected_version)
            print(f"Verified {len(apks)} APKs: {PACKAGE}, {args.expected_version}, versionCode {args.expected_code}")
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release compatibility check failed: {error}\n")


if __name__ == "__main__":
    main()
