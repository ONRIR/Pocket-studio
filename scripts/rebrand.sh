#!/usr/bin/env bash
# Puts the VSCandro name back on the text people read, after a merge of upstream VSCodroid brought
# the old name in again. Rule-based and safe to run twice: it touches only the string files, the
# extension titles and the product name, never code identifiers, URLs to upstream, LICENSE, NOTICE
# or the README credit line.
set -euo pipefail
cd "$(dirname "$0")/.."
files=$(ls android/app/src/main/res/values*/strings.xml; \
        find android/app/src/main/assets/extensions -name 'package.nls*.json' -o -path '*/l10n/*.json'; \
        echo branding/product.json)
changed=0
for f in $files; do
  [ -f "$f" ] || continue
  # (?<!rmyndharis/) keeps links to the upstream repository as they are.
  perl -pi -e 's/(?<!rmyndharis\/)VSCodroid/VSCandro/g' "$f"
done
git diff --stat | tail -1 || true
