#!/bin/sh
# Entrypoint shared by the example images.
#
# In gateway mode the API key is generated at bootstrap time (deploy/bootstrap/bootstrap.sh) and
# written to a file on a shared volume. When DBP_API_KEY_FILE is set, wait for that file, source it
# and export DBP_API_KEY from the variable named by DBP_API_KEY_VAR (e.g. ORDERS_SERVICE_API_KEY).
if [ -n "${DBP_API_KEY_FILE:-}" ] && [ -z "${DBP_API_KEY:-}" ]; then
    i=0
    while [ ! -s "$DBP_API_KEY_FILE" ] && [ "$i" -lt 120 ]; do
        echo "entrypoint: waiting for $DBP_API_KEY_FILE (bootstrap not finished yet)"
        sleep 5
        i=$((i + 1))
    done
    if [ -s "$DBP_API_KEY_FILE" ]; then
        set -a
        . "$DBP_API_KEY_FILE"
        set +a
        if [ -z "${DBP_API_KEY:-}" ] && [ -n "${DBP_API_KEY_VAR:-}" ]; then
            eval "DBP_API_KEY=\${$DBP_API_KEY_VAR:-}"
            export DBP_API_KEY
        fi
    fi
    if [ -z "${DBP_API_KEY:-}" ]; then
        echo "entrypoint: WARNING no API key found in $DBP_API_KEY_FILE (${DBP_API_KEY_VAR:-DBP_API_KEY}); starting anyway"
    fi
fi
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
