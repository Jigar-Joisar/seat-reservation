#!/bin/sh
# usage: ./burst.sh <BASE_URL> [burst.py options]
exec python3 "$(dirname "$0")/burst.py" "$@"
