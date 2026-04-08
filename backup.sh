#!/bin/sh
set -e
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
DEST="backup-$TIMESTAMP"
mkdir -p "$DEST"
if [ -f "chatapp.db" ]; then
  cp "chatapp.db" "$DEST/"
fi
if [ -d "uploads" ]; then
  cp -r "uploads" "$DEST/"
fi
printf "Backup created: %s\n" "$DEST"
