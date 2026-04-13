#!/usr/bin/env bash
# selectel-run.sh — Run a command on a Selectel Mobile Farm device
#
# Authenticates with Selectel, rents a device by spec filters, establishes
# an ADB-over-TCP connection, runs a user command, then always releases
# the device and removes the registered ADB key from Selectel.
#
# Usage:
#   export SELECTEL_USER="..."
#   export SELECTEL_PASSWORD="..."
#   export SELECTEL_DOMAIN="..."
#   export SELECTEL_PROJECT="..."
#
#   ./selectel-run.sh \
#     --manufacturer "SAMSUNG" \
#     --model        "Galaxy A34 5G" \
#     --version      "13" \
#     --cmd          "./gradlew :app:connectedDebugAndroidTest"
#
# See README.md for full documentation.

set -euo pipefail

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
readonly AUTH_URL="https://cloud.api.selcloud.ru/identity/v3/auth/tokens"
readonly FARM_API="https://api.selectel.ru/mobfarm/api"
readonly ADB_DEFAULT_KEY="$HOME/.android/adbkey"
readonly SCRIPT_NAME="$(basename "$0")"
readonly KEY_TITLE="selectel-run-$$"

# ---------------------------------------------------------------------------
# State variables — initialised before trap to avoid unbound variable errors
# ---------------------------------------------------------------------------
DEVICE_ASSIGNED=0   # 1 immediately after assign POST → 200
KEY_REGISTERED=0    # 1 immediately after key POST → 200
ADB_CONNECTED=0     # 1 immediately after adb connect is called (ensures disconnect in cleanup)
SERIAL=""
SLOT_ID=""
ADB_FINGERPRINT=""
ADB_PUBKEY=""       # contents of ~/.android/adbkey.pub; kept for DELETE body
REMOTE_URL=""
TOKEN=""
CMD_EXIT=0

# ---------------------------------------------------------------------------
# CLI flag defaults
# ---------------------------------------------------------------------------
ARG_MANUFACTURER=""
ARG_MODEL=""
ARG_VERSION=""
ARG_SDK=""
ARG_BILLING="minutes"
ARG_CMD=""
ARG_TIMEOUT=300
ARG_VERBOSE=0
ARG_DRY_RUN=0

# ---------------------------------------------------------------------------
# Logging helpers
# ---------------------------------------------------------------------------
log()     { echo "[$(date '+%H:%M:%S')] $*"; }
log_err() { echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2; }
log_warn(){ echo "[$(date '+%H:%M:%S')] WARNING: $*" >&2; }
log_verbose() { [[ "$ARG_VERBOSE" == "1" ]] && echo "[$(date '+%H:%M:%S')] [verbose] $*" || true; }

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
  cat >&2 <<EOF
Usage: $SCRIPT_NAME [OPTIONS]

Rent a Selectel Mobile Farm device, connect via ADB, run a command, release.

Required environment variables (credentials):
  SELECTEL_USER       Selectel account username / email
  SELECTEL_PASSWORD   Selectel account password
  SELECTEL_DOMAIN     Account domain (e.g. 123456_myaccount)
  SELECTEL_PROJECT    Project name for token scope

Required flags:
  --manufacturer NAME   Device manufacturer (e.g. SAMSUNG)
  --model NAME          Market name (e.g. "Galaxy A34 5G")
  --version VER         Android version (e.g. 13)
  --cmd COMMAND         Command to run once ADB is connected

Optional flags:
  --sdk LEVEL           Android SDK level (e.g. 33); added to filter if provided
  --billing TYPE        Billing type: minutes (default) or hours
  --timeout SECS        Max seconds to wait for ADB device state (default: 300)
  --verbose             Print full API responses
  --dry-run             Print curl commands without executing them
  -h, --help            Show this help

Examples:
  export SELECTEL_USER="da@example.com"
  export SELECTEL_PASSWORD="secret"
  export SELECTEL_DOMAIN="123456_myaccount"
  export SELECTEL_PROJECT="myproject"

  $SCRIPT_NAME \\
    --manufacturer "SAMSUNG" \\
    --model        "Galaxy A34 5G" \\
    --version      "13" \\
    --cmd          "./gradlew :app:connectedDebugAndroidTest"
EOF
  exit 1
}

# ---------------------------------------------------------------------------
# curl wrapper — respects --dry-run and --verbose
# Returns the HTTP response body; HTTP status checked by caller via -w "%{http_code}"
# ---------------------------------------------------------------------------
farm_curl() {
  local description="$1"; shift
  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    echo "[dry-run] $description: curl $*" >&2
    echo "200"   # fake success for dry-run
    return 0
  fi
  log_verbose "$description: curl $*"
  curl --silent --show-error --fail-with-body "$@"
}

# ---------------------------------------------------------------------------
# Retry helper — retries a function up to N times with a delay
# ---------------------------------------------------------------------------
retry() {
  local attempts="$1"; shift
  local delay="$1"; shift
  local description="$1"; shift
  local i
  for (( i=1; i<=attempts; i++ )); do
    if "$@"; then
      return 0
    fi
    if (( i < attempts )); then
      log_warn "$description: attempt $i/$attempts failed. Retrying in ${delay}s..."
      sleep "$delay"
    fi
  done
  log_warn "$description: all $attempts attempts failed."
  return 1
}

# ---------------------------------------------------------------------------
# Cleanup — always runs via trap
# ---------------------------------------------------------------------------
cleanup() {
  local exit_code=$?
  set +e
  trap '' EXIT INT TERM   # prevent recursive invocation

  log "--- Cleanup started ---"

  # 1. ADB disconnect
  if [[ "$ADB_CONNECTED" == "1" && -n "$REMOTE_URL" ]]; then
    log "Disconnecting ADB: $REMOTE_URL"
    if [[ "$ARG_DRY_RUN" == "1" ]]; then
      log "[dry-run] adb disconnect $REMOTE_URL"
    else
      adb disconnect "$REMOTE_URL" 2>/dev/null || true
    fi
  fi

  # 2. Device release
  if [[ "$DEVICE_ASSIGNED" == "1" ]]; then
    _release_device
  fi

  # 3. ADB key deletion from Selectel
  if [[ "$KEY_REGISTERED" == "1" && -n "$ADB_FINGERPRINT" ]]; then
    retry 3 5 "ADB key deletion" _delete_adb_key \
      || log_warn "ADB key not deleted (fingerprint: $ADB_FINGERPRINT). Remove manually."
  fi

  log "--- Cleanup done ---"
  exit "$exit_code"
}

_release_device() {
  if [[ -n "$SERIAL" && -n "$SLOT_ID" ]]; then
    log "Releasing device $SERIAL (slotId: $SLOT_ID) via v3..."
    retry 3 5 "Device release (v3)" _do_release_v3 \
      || _release_fallback
  elif [[ -n "$SERIAL" ]]; then
    log "SLOT_ID unknown — releasing device $SERIAL via v1 fallback..."
    retry 3 5 "Device release (v1 fallback)" _do_release_v1 \
      || _release_failed
  else
    echo "" >&2
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
    echo "WARNING: Device was assigned but serial is unknown." >&2
    echo "         Release the device manually in the"        >&2
    echo "         Selectel console to avoid billing leaks."  >&2
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
  fi
}

_do_release_v3() {
  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] DELETE $FARM_API/v3/devices/$SERIAL"
    return 0
  fi
  local http_status
  http_status=$(curl --silent --show-error \
    -X DELETE \
    -H "X-Auth-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"slotId\":\"$SLOT_ID\"}" \
    -w "%{http_code}" -o /dev/null \
    "$FARM_API/v3/devices/$SERIAL")
  log_verbose "Release v3 HTTP status: $http_status"
  [[ "$http_status" =~ ^2 ]]
}

_do_release_v1() {
  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] DELETE $FARM_API/v1/user/devices/$SERIAL"
    return 0
  fi
  local http_status
  http_status=$(curl --silent --show-error \
    -X DELETE \
    -H "X-Auth-Token: $TOKEN" \
    -w "%{http_code}" -o /dev/null \
    "$FARM_API/v1/user/devices/$SERIAL")
  log_verbose "Release v1 HTTP status: $http_status"
  [[ "$http_status" =~ ^2 ]]
}

_release_fallback() {
  log_warn "v3 release failed — trying v1 fallback for $SERIAL..."
  retry 3 5 "Device release (v1 fallback)" _do_release_v1 \
    || _release_failed
}

_release_failed() {
  echo "" >&2
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
  echo "ERROR: Failed to release device after all retries."           >&2
  echo "       Serial:  $SERIAL"                                       >&2
  echo "       SlotId:  $SLOT_ID"                                      >&2
  echo "       Release the device manually in the Selectel console."   >&2
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
}

_delete_adb_key() {
  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] DELETE $FARM_API/v2/keys/adb/$ADB_FINGERPRINT"
    return 0
  fi
  local body
  # jq not available here since we may be in cleanup after a jq failure;
  # build JSON manually (title and fingerprint are safe ASCII strings)
  body="{\"title\":\"${KEY_TITLE}\",\"publicKey\":\"${ADB_PUBKEY}\"}"
  local http_status
  http_status=$(curl --silent --show-error \
    -X DELETE \
    -H "X-Auth-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -w "%{http_code}" -o /dev/null \
    "$FARM_API/v2/keys/adb/$ADB_FINGERPRINT")
  log_verbose "Delete ADB key HTTP status: $http_status"
  [[ "$http_status" =~ ^2 ]]
}

# Register trap now — all state vars are initialised to safe defaults above
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --manufacturer) ARG_MANUFACTURER="$2"; shift 2 ;;
      --model)        ARG_MODEL="$2";        shift 2 ;;
      --version)      ARG_VERSION="$2";      shift 2 ;;
      --sdk)          ARG_SDK="$2";          shift 2 ;;
      --billing)      ARG_BILLING="$2";      shift 2 ;;
      --cmd)          ARG_CMD="$2";          shift 2 ;;
      --timeout)      ARG_TIMEOUT="$2";      shift 2 ;;
      --verbose)      ARG_VERBOSE=1;         shift   ;;
      --dry-run)      ARG_DRY_RUN=1;         shift   ;;
      -h|--help)      usage ;;
      *) log_err "Unknown flag: $1"; usage ;;
    esac
  done
}

validate_args() {
  local errors=0

  # Credentials from env
  for var in SELECTEL_USER SELECTEL_PASSWORD SELECTEL_DOMAIN SELECTEL_PROJECT; do
    if [[ -z "${!var:-}" ]]; then
      log_err "Environment variable $var is required but not set."
      (( errors++ )) || true
    fi
  done

  # Required flags
  [[ -z "$ARG_MANUFACTURER" ]] && { log_err "--manufacturer is required."; (( errors++ )) || true; }
  [[ -z "$ARG_MODEL"        ]] && { log_err "--model is required.";        (( errors++ )) || true; }
  [[ -z "$ARG_VERSION"      ]] && { log_err "--version is required.";      (( errors++ )) || true; }
  [[ -z "$ARG_CMD"          ]] && { log_err "--cmd is required.";          (( errors++ )) || true; }

  # Billing type validation
  if [[ "$ARG_BILLING" != "minutes" && "$ARG_BILLING" != "hours" ]]; then
    log_err "--billing must be 'minutes' or 'hours' (got: $ARG_BILLING)."
    (( errors++ )) || true
  fi

  (( errors > 0 )) && usage || true
}

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------
check_deps() {
  local missing=0
  for cmd in curl jq adb; do
    if ! command -v "$cmd" &>/dev/null; then
      log_err "Required tool not found: $cmd"
      (( missing++ )) || true
    fi
  done
  (( missing > 0 )) && { log_err "Install missing dependencies and retry."; exit 1; } || true
}

# ---------------------------------------------------------------------------
# Step 1 — Authenticate
# ---------------------------------------------------------------------------
do_auth() {
  log "Authenticating with Selectel..."

  local body
  body=$(jq -n \
    --arg user    "$SELECTEL_USER" \
    --arg domain  "$SELECTEL_DOMAIN" \
    --arg pass    "$SELECTEL_PASSWORD" \
    --arg project "$SELECTEL_PROJECT" \
    '{
      auth: {
        identity: {
          methods: ["password"],
          password: {
            user: {
              name: $user,
              domain: { name: $domain },
              password: $pass
            }
          }
        },
        scope: {
          project: {
            name: $project,
            domain: { name: $domain }
          }
        }
      }
    }')

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] POST $AUTH_URL"
    TOKEN="dry-run-token"
    return 0
  fi

  # Capture headers (-D -) and body together; parse token from headers
  local response_with_headers
  response_with_headers=$(curl --silent --show-error \
    -D - \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$body" \
    "$AUTH_URL")

  TOKEN=$(echo "$response_with_headers" \
    | grep -i "^x-subject-token:" \
    | tr -d '\r' \
    | awk '{print $2}')

  if [[ -z "$TOKEN" ]]; then
    log_err "Authentication failed — X-Subject-Token not found in response."
    log_verbose "Response: $response_with_headers"
    exit 1
  fi

  log "Authenticated successfully."
  log_verbose "Token: ${TOKEN:0:20}..."
}

# ---------------------------------------------------------------------------
# Step 2 — Register the default ADB public key with Selectel
# ---------------------------------------------------------------------------
do_adb_key() {
  # ADB always presents ~/.android/adbkey when connecting to a remote device.
  # We register that key with Selectel so the device trusts it.
  # If the key pair doesn't exist yet, generate it first.
  if [[ ! -f "$ADB_DEFAULT_KEY" ]]; then
    log "No ADB key found at $ADB_DEFAULT_KEY — generating..."
    adb keygen "$ADB_DEFAULT_KEY" 2>/dev/null
  fi

  if [[ ! -f "${ADB_DEFAULT_KEY}.pub" ]]; then
    log_err "ADB public key not found at ${ADB_DEFAULT_KEY}.pub"
    exit 1
  fi

  ADB_PUBKEY=$(cat "${ADB_DEFAULT_KEY}.pub")
  log_verbose "ADB public key: ${ADB_PUBKEY:0:40}..."

  log "Registering ADB public key with Selectel..."

  local body
  body=$(jq -n \
    --arg title  "$KEY_TITLE" \
    --arg pubkey "$ADB_PUBKEY" \
    '{ title: $title, publicKey: $pubkey }')

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] POST $FARM_API/v2/keys/adb"
    KEY_REGISTERED=1
    ADB_FINGERPRINT="dry-run-fingerprint"
    return 0
  fi

  local body_only http_status
  local tmp_body; tmp_body=$(mktemp)
  http_status=$(curl --silent --show-error \
    -X POST \
    -H "X-Auth-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -o "$tmp_body" \
    -w "%{http_code}" \
    "$FARM_API/v2/keys/adb")
  body_only=$(cat "$tmp_body"); rm -f "$tmp_body"

  if [[ "$http_status" =~ ^2 ]]; then
    # Set flag only when WE registered the key — cleanup will delete it
    KEY_REGISTERED=1
    ADB_FINGERPRINT=$(echo "$body_only" | jq -r '.publicKey.fingerprint')
    log "ADB key registered (fingerprint: $ADB_FINGERPRINT)."
    log_verbose "Key registration response: $body_only"
    return 0
  fi

  # 400 "already exists" — the key is known to Selectel (possibly from a previous run
  # or registered globally). Fetch our fingerprint from the list so cleanup can delete it.
  if [[ "$http_status" == "400" ]] && echo "$body_only" | grep -qi "already exists"; then
    log "ADB key already registered — fetching fingerprint from key list..."
    local list_body list_status
    local tmp_list; tmp_list=$(mktemp)
    list_status=$(curl --silent --show-error \
      -X GET \
      -H "X-Auth-Token: $TOKEN" \
      -o "$tmp_list" \
      -w "%{http_code}" \
      "$FARM_API/v2/keys/adb")
    list_body=$(cat "$tmp_list"); rm -f "$tmp_list"

    if [[ ! "$list_status" =~ ^2 ]]; then
      log_err "Failed to fetch ADB key list (HTTP $list_status): $list_body"
      exit 1
    fi

    log_verbose "Key list response: $list_body"

    # Response shape: {"success":true,"publicKeys":[{"fingerprint":"..","title":"..","userId":".."}]}
    # Items do NOT include the publicKey content — match by title prefix we control.
    ADB_FINGERPRINT=$(echo "$list_body" | jq -r '
        ( if type == "array" then .
          elif .publicKeys | type == "array" then .publicKeys
          elif .keys       | type == "array" then .keys
          elif .data       | type == "array" then .data
          elif .items      | type == "array" then .items
          else [ .[] | select(type == "object") ]
          end
        ) |
        [ .[] | select(type == "object") | select(.title | startswith("selectel-run-")) ] |
        .[0].fingerprint // empty
      ')

    if [[ -n "$ADB_FINGERPRINT" ]]; then
      # We found our own previously-registered key — take ownership so cleanup deletes it
      KEY_REGISTERED=1
      log "Found existing key (fingerprint: $ADB_FINGERPRINT)."
      return 0
    fi

    # Key list is empty or key belongs to another user/scope — we cannot manage it.
    # The key is already trusted by Selectel, so ADB will work fine. Skip deletion in cleanup.
    log_warn "ADB key already exists globally (not in our key list) — skipping ownership. Cleanup will not delete it."
    log_verbose "Key list response: $list_body"
    return 0
  fi

  log_err "ADB key registration failed (HTTP $http_status): $body_only"
  exit 1
}

# ---------------------------------------------------------------------------
# Step 3 — Assign device (v3 filters)
# ---------------------------------------------------------------------------
do_assign_device() {
  log "Assigning device (manufacturer=$ARG_MANUFACTURER, model=$ARG_MODEL, version=$ARG_VERSION)..."

  # Build filter object — include sdk only if provided
  local filter
  if [[ -n "$ARG_SDK" ]]; then
    filter=$(jq -n \
      --arg mfr  "$ARG_MANUFACTURER" \
      --arg name "$ARG_MODEL" \
      --arg ver  "$ARG_VERSION" \
      --arg sdk  "$ARG_SDK" \
      '{ manufacturer: $mfr, marketName: $name, version: $ver, sdk: $sdk, count: 1 }')
  else
    filter=$(jq -n \
      --arg mfr  "$ARG_MANUFACTURER" \
      --arg name "$ARG_MODEL" \
      --arg ver  "$ARG_VERSION" \
      '{ manufacturer: $mfr, marketName: $name, version: $ver, count: 1 }')
  fi

  local body
  body=$(jq -n \
    --argjson filter  "$filter" \
    --arg     billing "$ARG_BILLING" \
    '{ filters: [$filter], billingType: $billing }')

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] POST $FARM_API/v3/devices"
    DEVICE_ASSIGNED=1
    SERIAL="dry-run-serial"
    SLOT_ID="dry-run-slot-id"
    return 0
  fi

  local body_only http_status
  local tmp_body; tmp_body=$(mktemp)
  http_status=$(curl --silent --show-error \
    -X POST \
    -H "X-Auth-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -o "$tmp_body" \
    -w "%{http_code}" \
    "$FARM_API/v3/devices")
  body_only=$(cat "$tmp_body"); rm -f "$tmp_body"

  # Set flag BEFORE parsing — guarantees cleanup fires even if jq crashes
  DEVICE_ASSIGNED=1

  if [[ ! "$http_status" =~ ^2 ]]; then
    log_err "Device assignment failed (HTTP $http_status): $body_only"
    exit 1
  fi

  log_verbose "Assign response: $body_only"
  log "Device assigned. Fetching serial and slot ID..."

  # ---------------------------------------------------------------------------
  # Step 4 — Get slot ID and serial from project devices list
  # ---------------------------------------------------------------------------
  local devices_body devices_status
  local tmp_devices; tmp_devices=$(mktemp)
  devices_status=$(curl --silent --show-error \
    -X GET \
    -H "X-Auth-Token: $TOKEN" \
    -o "$tmp_devices" \
    -w "%{http_code}" \
    "$FARM_API/v3/devices")
  devices_body=$(cat "$tmp_devices"); rm -f "$tmp_devices"

  log_verbose "GET /v3/devices response: $devices_body"

  if [[ ! "$devices_status" =~ ^2 ]]; then
    log_warn "GET /v3/devices failed (HTTP $devices_status) — SLOT_ID unknown. Cleanup will use v1 fallback."
    return 0
  fi

  log_verbose "GET /v3/devices raw response: $devices_body"

  # Response may be a root array [...] or an object {"devices":[...]}
  # Try root array first, fall back to .devices[]
  if echo "$devices_body" | jq -e 'type == "array"' &>/dev/null; then
    SERIAL=$(echo "$devices_body" | jq -r '.[0].serial // empty')
    SLOT_ID=$(echo "$devices_body" | jq -r '.[0].meta.slot.id // empty')
  else
    SERIAL=$(echo "$devices_body" | jq -r '.devices[0].serial // empty')
    SLOT_ID=$(echo "$devices_body" | jq -r '.devices[0].meta.slot.id // empty')
  fi

  if [[ -z "$SERIAL" ]]; then
    log_warn "Could not parse device serial from /v3/devices response. Cleanup will attempt v1 fallback."
    log_warn "Response was: $devices_body"
  else
    log "Device serial: $SERIAL, slot ID: ${SLOT_ID:-<unknown>}"
  fi
}

# ---------------------------------------------------------------------------
# Step 5 — Assign device to user session (v1) — required before remoteConnect
# ---------------------------------------------------------------------------
do_assign_user_device() {
  if [[ -z "$SERIAL" ]]; then
    log_warn "Serial unknown — skipping user device assignment."
    return 0
  fi

  log "Assigning device $SERIAL to user session (v1)..."

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] POST $FARM_API/v1/user/devices"
    return 0
  fi

  local body_only http_status
  local tmp_body; tmp_body=$(mktemp)
  http_status=$(curl --silent --show-error \
    -X POST \
    -H "X-Auth-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"serial\":\"$SERIAL\",\"timeout\":300000}" \
    -o "$tmp_body" \
    -w "%{http_code}" \
    "$FARM_API/v1/user/devices")
  body_only=$(cat "$tmp_body"); rm -f "$tmp_body"

  log_verbose "User device assign response (HTTP $http_status): $body_only"

  if [[ ! "$http_status" =~ ^2 ]]; then
    log_err "User device assignment failed (HTTP $http_status): $body_only"
    exit 1
  fi

  log "Device assigned to user session."
}

# ---------------------------------------------------------------------------
# Step 6 — Enable WiFi
# ---------------------------------------------------------------------------
do_wifi_enable() {
  if [[ -z "$SERIAL" ]]; then
    log_warn "Serial unknown — skipping WiFi enable."
    return 0
  fi

  log "Enabling WiFi on device $SERIAL..."

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] PATCH $FARM_API/v3/devices/$SERIAL/settings"
    return 0
  fi

  local http_status
  http_status=$(curl --silent --show-error \
    -X PATCH \
    -H "X-Auth-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"wifi":true}' \
    -w "%{http_code}" -o /dev/null \
    "$FARM_API/v3/devices/$SERIAL/settings") || true

  if [[ "$http_status" =~ ^2 ]]; then
    log "WiFi enabled."
  else
    log_warn "WiFi enable returned HTTP $http_status — continuing anyway."
  fi
}

# ---------------------------------------------------------------------------
# Step 6 — Remote connect (get ADB host:port)
# ---------------------------------------------------------------------------
do_remote_connect() {
  log "Starting remote ADB session for device $SERIAL..."

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] POST $FARM_API/v1/user/devices/$SERIAL/remoteConnect"
    REMOTE_URL="192.0.2.1:5555"
    return 0
  fi

  local body_only http_status
  local tmp_body; tmp_body=$(mktemp)
  http_status=$(curl --silent --show-error \
    -X POST \
    -H "X-Auth-Token: $TOKEN" \
    -o "$tmp_body" \
    -w "%{http_code}" \
    "$FARM_API/v1/user/devices/$SERIAL/remoteConnect")
  body_only=$(cat "$tmp_body"); rm -f "$tmp_body"

  log_verbose "remoteConnect response: $body_only"

  if [[ ! "$http_status" =~ ^2 ]]; then
    log_err "remoteConnect failed (HTTP $http_status): $body_only"
    exit 1
  fi

  REMOTE_URL=$(echo "$body_only" | jq -r '.remoteConnectUrl // empty')

  if [[ -z "$REMOTE_URL" ]]; then
    log_err "remoteConnectUrl not found in response: $body_only"
    exit 1
  fi

  log "Remote ADB URL: $REMOTE_URL"
}

# ---------------------------------------------------------------------------
# Step 7 — ADB connect + wait for device state
# ---------------------------------------------------------------------------
do_adb_connect() {
  log "Connecting ADB to $REMOTE_URL..."

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] adb connect $REMOTE_URL"
    ADB_CONNECTED=1
    return 0
  fi

  adb connect "$REMOTE_URL" 2>/dev/null || true
  # Set flag immediately after adb connect — ensures disconnect runs in cleanup
  # even if the device never reaches ready state (timeout, auth failure, etc.)
  ADB_CONNECTED=1

  local elapsed=0
  local interval=5
  local state="unknown"
  log "Waiting for device to become ready (timeout: ${ARG_TIMEOUT}s)..."

  while (( elapsed < ARG_TIMEOUT )); do
    state=$(adb -s "$REMOTE_URL" get-state 2>/dev/null || echo "unknown")
    if [[ "$state" == "device" ]]; then
      log "Device ready (${elapsed}s elapsed)."
      return 0
    fi
    log_verbose "Device state: $state — waiting..."
    sleep "$interval"
    (( elapsed += interval )) || true
  done

  log_err "Device did not become ready within ${ARG_TIMEOUT}s (last state: $state)."
  exit 1
}

# ---------------------------------------------------------------------------
# Step 8 — Run user command
# ---------------------------------------------------------------------------
do_run_cmd() {
  log "Running command: $ARG_CMD"
  echo "---"

  if [[ "$ARG_DRY_RUN" == "1" ]]; then
    log "[dry-run] eval: $ARG_CMD"
    CMD_EXIT=0
    return 0
  fi

  set +e
  eval "$ARG_CMD"
  CMD_EXIT=$?
  set -e

  echo "---"
  if (( CMD_EXIT == 0 )); then
    log "Command succeeded (exit code 0)."
  else
    log_warn "Command exited with code $CMD_EXIT."
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  parse_args "$@"
  validate_args
  check_deps

  log "=== Selectel Mobile Farm Run ==="
  [[ "$ARG_DRY_RUN" == "1" ]] && log "[DRY RUN MODE — no API calls will be made]"

  do_auth
  do_adb_key
  do_assign_device
  do_assign_user_device
  do_wifi_enable
  do_remote_connect
  do_adb_connect
  do_run_cmd

  # Cleanup fires automatically via trap on exit
  exit "$CMD_EXIT"
}

main "$@"
