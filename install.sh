#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="2.0.9-SNAPSHOT"
BUNDLE_JAR="org.stalwart.identity.provisioning.connector.scim-${VERSION}.jar"
BUNDLE_GLOB="org.stalwart.identity.provisioning.connector.scim-*.jar"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_JAR="$SCRIPT_DIR/components/org.stalwart.identity.provisioning.connector.scim/target/$BUNDLE_JAR"
JAR_PATH="${1:-$DEFAULT_JAR}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

printf '\nStalwart SCIM Connector %s installer\n' "$VERSION"
printf 'Target type:\n  1) Linux VM\n  2) Docker container\n'
read -r -p 'Select [1/2]: ' TARGET

CONTAINER=""
if [[ "$TARGET" == "2" ]]; then
  read -r -p 'WSO2 container name: ' CONTAINER
  command -v docker >/dev/null 2>&1 || fail "docker command not found."
  docker inspect "$CONTAINER" >/dev/null 2>&1 || fail "container not found: $CONTAINER"
  RUNNING="$(docker inspect -f '{{.State.Running}}' "$CONTAINER")"
  [[ "$RUNNING" == "true" ]] || fail "container is not running: $CONTAINER"
  DROPINS="$(docker exec "$CONTAINER" sh -c 'find / -type d -path "*/repository/components/dropins" 2>/dev/null | head -n 1' || true)"
  if [[ -z "$DROPINS" ]]; then
    read -r -p 'Could not auto-detect dropins. Enter absolute dropins path inside container: ' DROPINS
  fi
elif [[ "$TARGET" == "1" ]]; then
  DROPINS="$(find /opt /usr/local /home /srv /root -type d -path '*/repository/components/dropins' -print -quit 2>/dev/null || true)"
  if [[ -z "$DROPINS" ]]; then
    read -r -p 'Could not auto-detect dropins. Enter absolute dropins path: ' DROPINS
  fi
else
  fail "select 1 or 2."
fi

[[ "$DROPINS" == /* ]] || fail "dropins path must be absolute."
[[ "$DROPINS" == */repository/components/dropins ]] || fail "path must end with /repository/components/dropins."
WSO2_HOME="${DROPINS%/repository/components/dropins}"
TOML_PATH="$WSO2_HOME/repository/conf/stalwart-scim-connector.toml"

[[ -f "$JAR_PATH" ]] || fail "connector JAR not found: $JAR_PATH"
command -v unzip >/dev/null 2>&1 || fail "unzip is required to verify the connector JAR."
MANIFEST="$(unzip -p "$JAR_PATH" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' || true)"
grep -Eq '^Bundle-Version: 2\.0\.9([.-]SNAPSHOT|\.SNAPSHOT)?$' <<<"$MANIFEST" || fail "selected JAR is not version 2.0.9-SNAPSHOT."
grep -Eq '^Bundle-SymbolicName: org\.stalwart\.identity\.provisioning\.connector\.scim([;[:space:]]|$)' <<<"$MANIFEST" || fail "selected JAR does not have the expected Stalwart SCIM bundle symbolic name."

printf '\nDetected installation target\n'
printf '  WSO2 home: %s\n' "$WSO2_HOME"
printf '  OSGi dropins: %s\n' "$DROPINS"
printf '  Optional TOML path (not created or modified): %s\n' "$TOML_PATH"
printf '  JAR source: %s\n' "$JAR_PATH"
printf '\nThe installer will back up only existing JAR files belonging to this connector.\n'
printf 'It will not create, modify, or delete the TOML file or any other WSO2 configuration.\n'
read -r -p 'Continue? [y/N]: ' CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Cancelled; no changes made."; exit 0; }

STAMP="$(date +%Y%m%d-%H%M%S)"

if [[ "$TARGET" == "1" ]]; then
  [[ -d "$WSO2_HOME" ]] || fail "WSO2 home does not exist: $WSO2_HOME"
  mkdir -p "$DROPINS"
  while IFS= read -r -d '' OLD; do
    mv -- "$OLD" "$OLD.backup-$STAMP"
    printf 'Backup created: %s.backup-%s\n' "$OLD" "$STAMP"
  done < <(find "$DROPINS" -maxdepth 1 -type f -name "$BUNDLE_GLOB" -print0)
  install -m 0644 "$JAR_PATH" "$DROPINS/$BUNDLE_JAR"
else
  REMOTE_TMP="/tmp/stalwart-scim-connector-${VERSION}-${STAMP}-$$.jar"
  docker cp "$JAR_PATH" "$CONTAINER:$REMOTE_TMP"
  if ! docker exec "$CONTAINER" sh -c '
    set -eu
    dropins=$1
    stamp=$2
    remote_tmp=$3
    jar_name=$4
    mkdir -p "$dropins"
    for old in "$dropins"/org.stalwart.identity.provisioning.connector.scim-*.jar; do
      [ -f "$old" ] || continue
      mv "$old" "$old.backup-$stamp"
      printf "Backup created: %s.backup-%s\n" "$old" "$stamp"
    done
    install -m 0644 "$remote_tmp" "$dropins/$jar_name"
    rm -f "$remote_tmp"
  ' sh "$DROPINS" "$STAMP" "$REMOTE_TMP" "$BUNDLE_JAR"; then
    docker exec "$CONTAINER" rm -f "$REMOTE_TMP" >/dev/null 2>&1 || true
    fail "installation inside the container failed; the temporary JAR was cleaned up where possible."
  fi
fi

printf '\nInstallation files placed successfully.\n'
printf 'Restart WSO2 to load the connector bundle.\n'
printf 'Configure the SCIM URL, API token, and claim mappings in the WSO2 connector UI.\n'
printf 'TOML is optional for advanced overrides; this installer leaves it untouched.\n'
