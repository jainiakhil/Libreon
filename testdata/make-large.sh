#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Generates a large plain-text file for testing the read-only large-file mode (never committed).
# Usage: testdata/make-large.sh [output] [megabytes]   (defaults: large-5mb.txt, 5)
set -euo pipefail
out="${1:-large-5mb.txt}"
mb="${2:-5}"
awk -v target=$((mb * 1024 * 1024)) 'BEGIN {
  size = 0
  for (i = 1; size < target; i++) {
    line = "Line " i ": The quick brown fox jumps over the lazy dog. needle-" (i % 1000)
    print line
    size += length(line) + 1
  }
}' > "$out"
echo "$out: $(wc -c < "$out") bytes, $(wc -l < "$out") lines"
