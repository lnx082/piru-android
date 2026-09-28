#!/usr/bin/env bash
#
# Put the bundled substance database in place.
#
# The database is not tracked in git; manifest.json beside it is. This reads the
# expected SHA-256 from that manifest and downloads the database only when what
# is on disk does not already match, so it is a no-op on a correct checkout and
# safe to run on every build.
#
# Mirrors the iOS repo's pipeline/fetch-db.sh, and the manifest is copied from
# there — the two projects must read the same content_version and expect the
# same bytes, or an export from one will not import into the other.
#
# USAGE
#   db/fetch-db.sh              # ensure the DB is present and correct
#   db/fetch-db.sh --force      # re-download even if the checksum matches
#
#   PIRU_DB_URL pins a single source (staging, a fork, a local file server).
#
set -euo pipefail

cd "$(dirname "$0")"

MANIFEST="manifest.json"
DB="piru-substances.sqlite"
DB_URL="${PIRU_DB_URL:-https://github.com/kageroumado/piru/releases/download/db/piru-substances.sqlite}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

[ -f "$MANIFEST" ] || {
    echo "error: $MANIFEST is missing — it is tracked, so this is a broken checkout" >&2
    exit 1
}

# The manifest is the authority on which database this checkout expects. Taking
# the checksum from the server instead would make the script always agree with
# whatever it just downloaded, which is not a check at all.
read -r EXPECTED_SHA EXPECTED_SIZE <<< "$(
    python3 -c "
import json
m = json.load(open('$MANIFEST'))
print(m['sqlite_sha256'], m['sqlite_size_bytes'])
"
)"

actual_sha() {
    python3 -c "
import hashlib, sys
h = hashlib.sha256()
with open(sys.argv[1], 'rb') as f:
    for chunk in iter(lambda: f.read(1 << 20), b''):
        h.update(chunk)
print(h.hexdigest())
" "$1" 2> /dev/null
}

if [ "$FORCE" -eq 0 ] && [ -f "$DB" ] && [ "$(actual_sha "$DB")" = "$EXPECTED_SHA" ]; then
    echo "fetch-db: $DB already matches the manifest ($EXPECTED_SHA)"
    exit 0
fi

# `gh` rather than curl: a plain request to the release URL gets connection-reset
# on some networks here, while the authenticated API download does not.
echo "fetch-db: downloading $DB"
if command -v gh > /dev/null 2>&1; then
    gh release download db --repo kageroumado/piru --pattern "$DB" --dir . --clobber
else
    curl -fL --retry 3 --max-time 1800 -o "$DB" "$DB_URL"
fi

GOT_SHA="$(actual_sha "$DB")"
GOT_SIZE="$(wc -c < "$DB" | tr -d ' ')"
if [ "$GOT_SHA" != "$EXPECTED_SHA" ]; then
    echo "error: checksum mismatch" >&2
    echo "  expected $EXPECTED_SHA ($EXPECTED_SIZE bytes)" >&2
    echo "  got      $GOT_SHA ($GOT_SIZE bytes)" >&2
    rm -f "$DB"
    exit 1
fi

echo "fetch-db: ok — $DB matches the manifest"
