#!/bin/sh
# Starts Record Labs installed under /opt/record-labs.
# Java reads file names with the system locale; desktops set a UTF-8 one, but
# a bare session may not, which would break non-ASCII song and folder names.
if [ -z "${LANG:-}${LC_ALL:-}${LC_CTYPE:-}" ]; then export LANG=C.UTF-8; fi
exec "/opt/record-labs/bin/Record Labs" "$@"
