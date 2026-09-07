#!/usr/bin/env bash

set -euo pipefail

repository="${1:-}"
key_file="${2:-signingkey.jks}"
key_alias="manhuaextensions"

if ! command -v keytool >/dev/null 2>&1; then
  echo "Java keytool is required. Install JDK 17 first." >&2
  exit 1
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI is required: https://cli.github.com/" >&2
  exit 1
fi
if [ -z "$repository" ]; then
  repository=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
fi
if [ -e "$key_file" ]; then
  echo "Refusing to overwrite existing key: $key_file" >&2
  exit 1
fi

read -r -s -p "New signing-key password (at least 6 characters): " key_password
printf '\n'
if [ "${#key_password}" -lt 6 ]; then
  echo "Password must contain at least 6 characters." >&2
  exit 1
fi

keytool -genkeypair \
  -keystore "$key_file" \
  -storetype JKS \
  -storepass "$key_password" \
  -keypass "$key_password" \
  -alias "$key_alias" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 36500 \
  -dname "CN=Private Mihon Extensions" \
  -noprompt

base64 < "$key_file" | tr -d '\n' | gh secret set SIGNING_KEY --repo "$repository"
printf '%s' "$key_alias" | gh secret set ALIAS --repo "$repository"
printf '%s' "$key_password" | gh secret set KEY_STORE_PASSWORD --repo "$repository"
printf '%s' "$key_password" | gh secret set KEY_PASSWORD --repo "$repository"

fingerprint=$(keytool -exportcert -keystore "$key_file" -alias "$key_alias" -storepass "$key_password" | shasum -a 256 | cut -d' ' -f1)
echo "GitHub Actions signing secrets configured for $repository."
echo "Signing fingerprint: $fingerprint"
echo "Back up $key_file securely. Losing it prevents trusted updates."
