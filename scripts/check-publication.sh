#!/usr/bin/env bash
# Check tracked/staged paths, not ignored files retained on a maintainer's machine.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
violations=0
while IFS= read -r -d '' tracked_path; do
  case "$tracked_path" in
    app/*|SAMPLE_APP.md|sample-config.properties.example|local.properties|*/local.properties|*.apk|*.aab|*.jks|*.keystore|*.p12|*.pfx|*.pem|*.key)
      printf 'Private or generated file is tracked: %s\n' "$tracked_path" >&2
      violations=$((violations + 1))
      ;;
  esac
  case "${tracked_path##*/}" in
    [Aa][Gg][Ee][Nn][Tt][Ss].[Mm][Dd])
      printf 'Local engineering notes are tracked: %s\n' "$tracked_path" >&2
      violations=$((violations + 1))
      ;;
  esac
done < <(git ls-files --cached -z)
if ((violations > 0)); then
  exit 1
fi
printf 'Public source boundary check passed.\n'
if rg -n '/app/v1/' sdk/src/main sdk/src/debug; then
  printf 'Production SDK contains a removed V1 route.\n' >&2
  exit 1
fi
