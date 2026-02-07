#!/bin/bash
# Two workarounds for running Sparrow Server on Umbrel:
#
# 1. TTY: Sparrow's lanterna terminal library requires /dev/tty. Umbrel ignores
#    docker-compose tty:true, so we use `script` to create a pseudo-TTY.
#
# 2. Unix domain socket: Docker's AppArmor blocks binding Unix domain sockets on
#    bind-mounted volumes. We use /tmp/sparrow-data as the data directory so the
#    lock file is created on the overlay filesystem. Persistent files from the
#    volume (/data) are symlinked in.

set -e

PERSISTENT_DIR="/data"
WORKING_DIR="/tmp/sparrow-data"

mkdir -p "$WORKING_DIR"

# Symlink all existing persistent files/directories from the volume
for item in "$PERSISTENT_DIR"/*; do
  [ -e "$item" ] && ln -sf "$item" "$WORKING_DIR/"
done

# Use script to provide a pseudo-TTY, then exec Sparrow
exec script -qc "/opt/sparrowserver/Sparrow/bin/Sparrow -d $WORKING_DIR $*" /dev/null
