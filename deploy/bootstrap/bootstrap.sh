#!/bin/sh
# =====================================================================================
# bootstrap.sh -- one-shot initialisation of the control plane for the demo stack.
#
#   1. wait until the control plane is healthy
#   2. POST /api/v1/import  with platform-config.json  (idempotent: upsert by name)
#   3. create an API key for every application in DBP_BOOTSTRAP_APPS (unless already generated)
#      and write them to $DBP_GENERATED_DIR/examples.env  as  <APP_NAME>_API_KEY=dbp_...
#   4. trigger a dictionary crawl of every registered database (best effort)
#
# Runs in curlimages/curl (busybox sh, no jq). JSON is parsed with grep/sed; when jq happens to be
# on the PATH it is used instead. POSIX sh only.
#
# Environment
#   DBP_CONTROL_PLANE_URL     http://control-plane:8080
#   DBP_SERVICE_TOKEN         X-DBP-Service-Token (internal endpoints; harmless on public ones)
#   DBP_ADMIN_USER/PASSWORD   basic auth when DBP_SECURITY_MODE=basic (optional)
#   DBP_BOOTSTRAP_CONFIG      /bootstrap/platform-config.json
#   DBP_GENERATED_DIR         /generated
#   DBP_BOOTSTRAP_APPS        "orders-service reporting-batch"
#   DBP_BOOTSTRAP_FORCE       true = generate new API keys even if examples.env exists
#   DBP_BOOTSTRAP_WAIT_SECONDS  300
# =====================================================================================
set -u

CP="${DBP_CONTROL_PLANE_URL:-http://control-plane:8080}"
TOKEN="${DBP_SERVICE_TOKEN:-dev-service-token}"
CONFIG="${DBP_BOOTSTRAP_CONFIG:-/bootstrap/platform-config.json}"
OUT_DIR="${DBP_GENERATED_DIR:-/generated}"
APPS="${DBP_BOOTSTRAP_APPS:-orders-service reporting-batch}"
FORCE="${DBP_BOOTSTRAP_FORCE:-false}"
WAIT="${DBP_BOOTSTRAP_WAIT_SECONDS:-300}"
ENV_FILE="$OUT_DIR/examples.env"
TMP="${TMPDIR:-/tmp}/dbp-bootstrap.$$"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

log() { echo "[bootstrap] $*"; }
die() { log "ERROR: $*"; exit 1; }

AUTH=""
if [ -n "${DBP_ADMIN_USER:-}" ]; then
    AUTH="-u ${DBP_ADMIN_USER}:${DBP_ADMIN_PASSWORD:-}"
fi

# api METHOD PATH [JSON-FILE]  -> prints the HTTP status, body goes to $TMP/body
api() {
    method="$1"; path="$2"; data="${3:-}"
    if [ -n "$data" ]; then
        # shellcheck disable=SC2086
        curl -sS $AUTH -o "$TMP/body" -w '%{http_code}' -X "$method" \
             -H 'Content-Type: application/json' -H 'Accept: application/json' \
             -H "X-DBP-Service-Token: $TOKEN" --data-binary "@$data" "$CP/api/v1$path" 2>"$TMP/err" \
            || { echo "000"; return; }
    else
        # shellcheck disable=SC2086
        curl -sS $AUTH -o "$TMP/body" -w '%{http_code}' -X "$method" \
             -H 'Accept: application/json' -H "X-DBP-Service-Token: $TOKEN" "$CP/api/v1$path" 2>"$TMP/err" \
            || { echo "000"; return; }
    fi
}

# Normalise JSON to one line without decorative whitespace, one top-level object per line.
flatten() {
    tr -d '\n\r\t' | sed -e 's/ *: */:/g' -e 's/ *, */,/g' -e 's/{ */{/g' -e 's/ *}/}/g' -e 's/\[ */[/g' -e 's/ *\]/]/g' \
        | sed -e 's/},{/}\n{/g'
}

# json_value FILE KEY -> first string value of "KEY" in the file
json_value() {
    flatten < "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

# id_by_name FILE NAME -> id of the object whose "name" equals NAME
id_by_name() {
    if command -v jq >/dev/null 2>&1; then
        jq -r --arg n "$2" '(if type=="array" then . else (.items // []) end) | .[] | select(.name==$n) | .id' "$1" | head -1
    else
        flatten < "$1" | grep -F "\"name\":\"$2\"" | head -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
    fi
}

# all_ids FILE -> every "id" of the top-level objects
all_ids() {
    if command -v jq >/dev/null 2>&1; then
        jq -r '(if type=="array" then . else (.items // []) end) | .[] | .id' "$1"
    else
        flatten < "$1" | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | sort -u
    fi
}

env_var_name() {
    echo "$1" | tr '[:lower:]-' '[:upper:]_' | sed 's/[^A-Z0-9_]/_/g'
}

# ------------------------------------------------------------------------------------
# 1. wait for the control plane
# ------------------------------------------------------------------------------------
log "waiting for $CP (max ${WAIT}s)"
elapsed=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$CP/actuator/health" 2>/dev/null)" = "200" ]; do
    if [ "$elapsed" -ge "$WAIT" ]; then
        die "control plane not healthy after ${WAIT}s"
    fi
    sleep 5
    elapsed=$((elapsed + 5))
done
log "control plane is up"

# ------------------------------------------------------------------------------------
# 2. import the configuration (idempotent)
# ------------------------------------------------------------------------------------
[ -f "$CONFIG" ] || die "configuration file $CONFIG not found"
status="$(api POST /import "$CONFIG")"
case "$status" in
    2*) log "import OK (HTTP $status): $(head -c 300 "$TMP/body")" ;;
    *)  log "import failed (HTTP $status): $(head -c 1000 "$TMP/body") $(cat "$TMP/err" 2>/dev/null)"
        die "aborting (fix deploy/bootstrap/platform-config.json or the control plane import contract)" ;;
esac

# ------------------------------------------------------------------------------------
# 3. API keys for the example applications
# ------------------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
need_keys=false
if [ "$FORCE" = "true" ] || [ ! -s "$ENV_FILE" ]; then
    need_keys=true
else
    for app in $APPS; do
        var="$(env_var_name "$app")_API_KEY"
        if ! grep -q "^${var}=dbp_" "$ENV_FILE"; then
            need_keys=true
        fi
    done
fi

if [ "$need_keys" = "true" ]; then
    status="$(api GET /applications)"
    case "$status" in 2*) ;; *) die "GET /applications failed (HTTP $status): $(head -c 500 "$TMP/body")" ;; esac
    cp "$TMP/body" "$TMP/applications.json"

    : > "$TMP/examples.env"
    {
        echo "# Generated by deploy/bootstrap/bootstrap.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ). API keys of the example applications."
        echo "# Used by the gateway-mode examples (docker-entrypoint.sh reads DBP_API_KEY_FILE / DBP_API_KEY_VAR)."
    } >> "$TMP/examples.env"
    for app in $APPS; do
        id="$(id_by_name "$TMP/applications.json" "$app")"
        [ -n "$id" ] || die "application '$app' not found after import"
        printf '{"label":"compose-%s"}' "$(date -u +%Y%m%d%H%M%S)" > "$TMP/key-request.json"
        status="$(api POST "/applications/$id/api-keys" "$TMP/key-request.json")"
        case "$status" in 2*) ;; *) die "creating an API key for $app failed (HTTP $status): $(head -c 500 "$TMP/body")" ;; esac
        key="$(json_value "$TMP/body" apiKey)"
        [ -n "$key" ] || die "no apiKey in the response for $app: $(head -c 300 "$TMP/body")"
        echo "$(env_var_name "$app")_API_KEY=$key" >> "$TMP/examples.env"
        log "API key created for $app (id $id, prefix $(json_value "$TMP/body" prefix))"
    done
    cp "$TMP/examples.env" "$ENV_FILE.tmp" && mv "$ENV_FILE.tmp" "$ENV_FILE"
    chmod 644 "$ENV_FILE"
    log "wrote $ENV_FILE"
else
    log "API keys already present in $ENV_FILE (set DBP_BOOTSTRAP_FORCE=true to regenerate)"
fi

# ------------------------------------------------------------------------------------
# 4. kick off dictionary crawls (best effort; the collector also runs on its own schedule)
# ------------------------------------------------------------------------------------
status="$(api GET /databases)"
if case "$status" in 2*) true ;; *) false ;; esac; then
    cp "$TMP/body" "$TMP/databases.json"
    printf '{"what":"DICTIONARY"}' > "$TMP/collect.json"
    for id in $(all_ids "$TMP/databases.json"); do
        s="$(api POST "/databases/$id/collect" "$TMP/collect.json")"
        log "dictionary crawl for database $id: HTTP $s"
    done
else
    log "could not list databases (HTTP $status), skipping crawl trigger"
fi

date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT_DIR/bootstrap.done"
log "done"
exit 0
