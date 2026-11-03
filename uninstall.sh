#!/usr/bin/env bash
set -Eeuo pipefail

printf '\nStalwart SCIM Connector uninstaller\n'
printf 'Target type:\n  1) Linux VM\n  2) Docker container\n'
read -r -p 'Select [1/2]: ' TARGET
CONTAINER=""
if [[ "$TARGET" == "2" ]]; then
  read -r -p 'WSO2 container name: ' CONTAINER
  command -v docker >/dev/null || { echo "ERROR: docker command not found."; exit 1; }
  docker inspect "$CONTAINER" >/dev/null 2>&1 || { echo "ERROR: container not found."; exit 1; }
  DROPINS="$(docker exec "$CONTAINER" sh -lc 'find / -type d -path "*/repository/components/dropins" 2>/dev/null | head -n 1')"
  if [[ -z "$DROPINS" ]]; then read -r -p 'Enter absolute dropins path inside container: ' DROPINS; fi
elif [[ "$TARGET" == "1" ]]; then
  DROPINS="$(find /opt /usr/local /home /srv -type d -path '*/repository/components/dropins' -print -quit 2>/dev/null || true)"
  if [[ -z "$DROPINS" ]]; then read -r -p 'Enter absolute dropins path: ' DROPINS; fi
else
  echo "ERROR: select 1 or 2."; exit 1
fi
printf '\nDetected dropins: %s\n' "$DROPINS"
echo "Only JARs whose name starts with org.stalwart.identity.provisioning.connector.scim- will be moved to a .disabled backup."
echo "The connector TOML file and every unrelated file will be left untouched."
read -r -p 'Continue? [y/N]: ' CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Cancelled; no changes made."; exit 0; }
STAMP="$(date +%Y%m%d-%H%M%S)"
if [[ "$TARGET" == "1" ]]; then
  shopt -s nullglob
  for old in "$DROPINS"/org.stalwart.identity.provisioning.connector.scim-*.jar; do
    mv -- "$old" "$old.disabled-$STAMP"
  done
else
  docker exec "$CONTAINER" sh -lc "set -eu
    for old in '$DROPINS'/org.stalwart.identity.provisioning.connector.scim-*.jar; do
      [ -f \"\$old\" ] || continue
      mv \"\$old\" \"\$old.disabled-$STAMP\"
    done"
fi
echo "Connector JAR disabled. Restart WSO2. TOML configuration was preserved."
