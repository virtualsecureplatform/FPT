#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FPT_BUFFERED_ACCELERATOR=1 \
    exec "$repo_root/tools/measure_fpt_buffered_blind_rotate_schedule.sh" "$@"
