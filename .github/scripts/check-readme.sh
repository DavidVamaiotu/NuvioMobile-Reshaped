#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Check identity and destinations without freezing the README's wording or features.
for required in \
  '<h1>NuvioTV Reshaped — Mobile</h1>' \
  'https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/releases' \
  'https://github.com/DavidVamaiotu/NuvioMobile-Reshaped.git' \
  './LICENSE'; do
  if ! grep -Fq -- "$required" README.md; then
    echo "README.md must retain NuvioTV Reshaped branding and fork links: $required" >&2
    exit 1
  fi
done

if grep -Eq 'https://github.com/(tapframe/NuvioTV|NuvioMedia/NuvioMobile)/releases' README.md; then
  echo "README.md downloads must point to Reshaped releases." >&2
  exit 1
fi
