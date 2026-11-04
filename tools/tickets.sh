#!/bin/sh
# The tracker is tools/tickets.mjs (node, the same on every OS); this only calls it.
exec node "$(dirname "$0")/tickets.mjs" "$@"
