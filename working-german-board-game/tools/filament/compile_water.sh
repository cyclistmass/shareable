#!/usr/bin/env bash
# Use matc from Filament 1.66.1, matching app/build.gradle.kts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
"${MATC:-matc}" -p mobile -a opengl \
  -o "$ROOT/app/src/main/assets/filament/live_water.filamat" \
  "$ROOT/tools/filament/live_water.mat"
