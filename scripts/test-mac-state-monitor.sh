#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$PROJECT_ROOT/.build/state-monitor-test"
mkdir -p "$BUILD"
swiftc -O -module-cache-path "$BUILD/module-cache" \
  "$PROJECT_ROOT/mac/StateDirectoryMonitor.swift" "$PROJECT_ROOT/tests/mac-state-monitor/main.swift" \
  -o "$BUILD/test-state-monitor"
"$BUILD/test-state-monitor"
