#!/bin/sh
# Starts sshd (it daemonizes), then hands over to the stock dind entrypoint.
set -e
/usr/sbin/sshd -E /var/log/sshd.log
exec dockerd-entrypoint.sh "$@"
