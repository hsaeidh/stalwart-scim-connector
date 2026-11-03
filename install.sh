#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_JAR="$SCRIPT_DIR/components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.7-SNAPSHOT.jar"
JAR_PATH="${1:-$DEFAULT_JAR}"

printf '\nStalwart SCIM Connector 2.0.7 installer\n'
printf 'Target type:\n  1) Linux VM\n  2) Docker container\n'
read -r -p 'Select [1/2]: ' TARGET

CONTAINER=""
if [[ "$TARGET" == "2" ]]; then
  read -r -p 'WSO2 container name: ' CONTAINER
  command -v docker >/dev/null || { echo "ERROR: docker command not found."; exit 1; }
  docker inspect "$CONTAINER" >/dev/null 2>&1 || { echo "ERROR: container not found: $CONTAINER"; exit 1; }
  DROPINS="$(docker exec "$CONTAINER" sh -lc 'find / -type d -path "*/repository/components/dropins" 2>/dev/null | head -n 1')"
  if [[ -z "$DROPINS" ]]; then
    read -r -p 'Could not auto-detect dropins. Enter absolute dropins path inside container: ' DROPINS
  fi
elif [[ "$TARGET" == "1" ]]; then
  DROPINS="$(find /opt /usr/local /home /srv -type d -path '*/repository/components/dropins' -print -quit 2>/dev/null || true)"
  if [[ -z "$DROPINS" ]]; then
    read -r -p 'Could not auto-detect dropins. Enter absolute dropins path: ' DROPINS
  fi
else
  echo "ERROR: select 1 or 2."
  exit 1
fi

[[ "$DROPINS" == /* ]] || { echo "ERROR: dropins path must be absolute."; exit 1; }
WSO2_HOME="${DROPINS%/repository/components/dropins}"
TOML_PATH="$WSO2_HOME/repository/conf/stalwart-scim-connector.toml"

if [[ "$TARGET" == "1" ]]; then
  [[ -d "$DROPINS" ]] || { echo "ERROR: dropins directory does not exist: $DROPINS"; exit 1; }
fi
[[ -f "$JAR_PATH" ]] || { echo "ERROR: connector JAR not found: $JAR_PATH"; exit 1; }
command -v unzip >/dev/null || { echo "ERROR: unzip is required to verify the connector JAR."; exit 1; }
unzip -p "$JAR_PATH" META-INF/MANIFEST.MF | tr -d '\r' | grep -q 'Bundle-Version: 2.0.7.SNAPSHOT' || {
  echo "ERROR: selected JAR is not version 2.0.7-SNAPSHOT."; exit 1;
}

printf '\nDetected installation target\n'
printf '  WSO2 home: %s\n' "$WSO2_HOME"
printf '  OSGi dropins: %s\n' "$DROPINS"
printf '  Connector TOML: %s\n' "$TOML_PATH"
printf '  JAR source: %s\n' "$JAR_PATH"
printf '\nThe installer will back up only an existing connector JAR with the same bundle name.\n'
printf 'Other JARs and existing TOML content will not be overwritten.\n'
read -r -p 'Continue? [y/N]: ' CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Cancelled; no changes made."; exit 0; }

STAMP="$(date +%Y%m%d-%H%M%S)"
TEMPLATE="$(cat <<'EOF'
[stalwart-scim]
# Set secrets with restrictive file permissions. TOML values override the WSO2 UI fields.
# scim-base-url = "https://mail.example.com/scim/v2"
# scim-bearer-token = "REPLACE_WITH_STALWART_API_KEY"
# scim-user-store-domain = "PRIMARY"
# enable-claim-mapping = true
# username-claim-uri = "http://wso2.org/claims/emailaddress"
# external-id-claim-uri = "http://wso2.org/claims/username"
# given-name-claim-uri = "http://wso2.org/claims/givenname"
# family-name-claim-uri = "http://wso2.org/claims/lastname"
# display-name-claim-uri = "http://wso2.org/claims/displayname"
# enable-profile-updates = true
# profile-update-scim-url = ""
# max-concurrent-requests = 8
# active-claim-uri = ""
# invert-active-claim = false
# email-membership-group = "Email"
# provisioning-mode = "INDIVIDUAL"
# bulk-fallback-to-individual = true
# bulk-request-timeout-ms = 30000
# bulk-max-payload-bytes = 900000
EOF
)"

if [[ "$TARGET" == "1" ]]; then
  mkdir -p "$DROPINS" "$(dirname "$TOML_PATH")"
  find "$DROPINS" -maxdepth 1 -type f -name 'org.stalwart.identity.provisioning.connector.scim-*.jar' -print0 |
    while IFS= read -r -d '' OLD; do mv -- "$OLD" "$OLD.backup-$STAMP"; done
  install -m 0644 "$JAR_PATH" "$DROPINS/"
  if [[ ! -f "$TOML_PATH" ]]; then
    printf '%s\n' "$TEMPLATE" > "$TOML_PATH"
    chmod 0600 "$TOML_PATH"
  elif ! grep -qx '\[stalwart-scim\]' "$TOML_PATH"; then
    printf '\n%s\n' "$TEMPLATE" >> "$TOML_PATH"
    chmod 0600 "$TOML_PATH"
  fi
else
  REMOTE_TMP="/tmp/stalwart-scim-connector-2.0.7.jar"
  docker cp "$JAR_PATH" "$CONTAINER:$REMOTE_TMP"
  docker exec "$CONTAINER" sh -lc "set -eu
    mkdir -p '$DROPINS' '$(dirname "$TOML_PATH")'
    for old in '$DROPINS'/org.stalwart.identity.provisioning.connector.scim-*.jar; do
      [ -f \"\$old\" ] || continue
      mv \"\$old\" \"\$old.backup-$STAMP\"
    done
    install -m 0644 '$REMOTE_TMP' '$DROPINS/org.stalwart.identity.provisioning.connector.scim-2.0.7-SNAPSHOT.jar'
    rm -f '$REMOTE_TMP'
    if [ ! -f '$TOML_PATH' ]; then
      cat > '$TOML_PATH' <<'TOML_EOF'
$TEMPLATE
TOML_EOF
      chmod 0600 '$TOML_PATH'
    elif ! grep -qx '\[stalwart-scim\]' '$TOML_PATH'; then
      printf '\n%s\n' '$TEMPLATE' >> '$TOML_PATH'
      chmod 0600 '$TOML_PATH'
    fi"
fi

echo
echo "Installation files placed successfully."
echo "Restart WSO2 to load the connector bundle."
echo "Edit $TOML_PATH and set scim-base-url and scim-bearer-token."
echo "The TOML file is read from the standard carbon.home/repository/conf path."
