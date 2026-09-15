#!/bin/sh
set -e

case "$HSM_MODULE_LIBRARY" in
    *libsofthsm2*)
        echo "Detected SoftHSMv2 library path. Initializing SoftHSM Token..."
        sh /softhsm/init-slot.sh
        ;;
esac

echo "Starting Spring Boot Application..."
exec "$@"
