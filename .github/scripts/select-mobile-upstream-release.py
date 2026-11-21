#!/usr/bin/env python3
"""Select the highest published SemVer, even when an older line ships later."""

import json
import re
import sys


VERSION = re.compile(
    r"^[vV]?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)


def version_key(tag):
    match = VERSION.fullmatch(tag)
    if not match:
        return None
    major, minor, patch, prerelease = match.groups()
    identifiers = []
    for part in prerelease.split(".") if prerelease else []:
        if part.isdigit():
            if len(part) > 1 and part.startswith("0"):
                return None
            identifiers.append((0, int(part)))
        else:
            identifiers.append((1, part))
    return (int(major), int(minor), int(patch), prerelease is None, tuple(identifiers))


def select_release(releases):
    # gh api --paginate | jq -s wraps each page in another array.
    rows = [row for page in releases for row in (page if isinstance(page, list) else [page])]
    candidates = []
    for release in rows:
        if release.get("draft"):
            continue
        tag = release.get("tag_name", "")
        key = version_key(tag)
        if key is not None:
            candidates.append((key, tag))
    if not candidates:
        raise ValueError("No published official Nuvio semantic release found")
    return max(candidates, key=lambda candidate: candidate[0])[1]


if __name__ == "__main__":
    try:
        print(select_release(json.load(sys.stdin)))
    except ValueError as error:
        sys.exit(str(error))
