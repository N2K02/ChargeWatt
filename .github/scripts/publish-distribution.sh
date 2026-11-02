#!/usr/bin/env bash
# Publish signed APKs from this private source repository to the public website.
set -euo pipefail

PUBLIC_REPO="N2K02/n2k02.github.io"
: "${GH_TOKEN:?ANDROID_DISTRIBUTION_TOKEN must be configured}"
: "${VERSION:?VERSION must be set}"
: "${VERSION_CODE:?VERSION_CODE must be set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER must be set}"

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "::error::Invalid APK versionCode" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]$ ]] || { echo "::error::Invalid app version" >&2; exit 1; }

REPO_NAME="${GITHUB_REPOSITORY##*/}"
SLUG=$(printf '%s' "$REPO_NAME" | tr '[:upper:]' '[:lower:]')
APP_LABEL="${APP:-$REPO_NAME}"
APK_BASE="${APK_NAME:-$APP_LABEL-$VERSION}"
APK_BASE="${APK_BASE%.apk}"
APK="$APK_BASE.apk"
test -s "$APK"

CHANNEL="stable"
VERSION_NAME="$VERSION"
PUBLIC_TAG="$SLUG-v$VERSION-build-$GITHUB_RUN_NUMBER"
MANIFEST="$SLUG/updates/stable.json"
RELEASE_FLAGS=(--latest)
if [[ "$GITHUB_EVENT_NAME" == "pull_request" ]]; then
  : "${PR_NUMBER:?PR_NUMBER must be set for experimental publishing}"
  CHANNEL="experimental"
  VERSION_NAME="$VERSION-experimental.$GITHUB_RUN_NUMBER"
  if [[ "$SLUG" == "chargewatt" ]]; then
    PUBLIC_TAG="$SLUG-exp-pr-$PR_NUMBER-run-$GITHUB_RUN_NUMBER"
  else
    PUBLIC_TAG="$SLUG-experimental-pr-$PR_NUMBER-run-$GITHUB_RUN_NUMBER"
  fi
  MANIFEST="$SLUG/updates/experimental.json"
  RELEASE_FLAGS=(--prerelease --latest=false)
fi

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c '%s' "$APK")
NOTES=$( { grep '^- ' release-notes.md || true; } | head -10 | sed 's/^- //' | jq -R -s 'split("\n") | map(select(length > 0))')
APK_URL="https://github.com/$PUBLIC_REPO/releases/download/$PUBLIC_TAG/$APK"
PAGE_URL="https://github.com/$PUBLIC_REPO/releases/tag/$PUBLIC_TAG"

# Keep the APK downloadable without GitHub access to this private source repo.
if gh release view "$PUBLIC_TAG" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
  gh release upload "$PUBLIC_TAG" "$APK" --repo "$PUBLIC_REPO" --clobber
else
  gh release create "$PUBLIC_TAG" "$APK" --repo "$PUBLIC_REPO" --target main \
    --title "$APP_LABEL $VERSION ($CHANNEL)" --notes-file release-notes.md "${RELEASE_FLAGS[@]}"
fi

jq -n \
  --arg channel "$CHANNEL" \
  --arg tag "$PUBLIC_TAG" \
  --arg versionName "$VERSION_NAME" \
  --arg apkName "$APK" \
  --arg apkUrl "$APK_URL" \
  --arg pageUrl "$PAGE_URL" \
  --arg sha256 "$SHA" \
  --argjson versionCode "$VERSION_CODE" \
  --argjson apkSize "$SIZE" \
  --argjson notes "$NOTES" \
  '{channel:$channel,tag:$tag,versionName:$versionName,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:$notes}' > update-manifest.json

CONTENT=$(base64 -w0 update-manifest.json)
for attempt in 1 2 3 4 5; do
  CURRENT=$(gh api "repos/$PUBLIC_REPO/contents/$MANIFEST?ref=main" 2>/dev/null || true)
  CURRENT_SHA=$(jq -r '.sha // empty' <<<"$CURRENT")
  CURRENT_B64=$(jq -r '.content // empty' <<<"$CURRENT" | tr -d '\n')
  CURRENT_JSON=$(printf '%s' "$CURRENT_B64" | base64 -d 2>/dev/null || true)
  OLD_CODE=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON" 2>/dev/null || echo 0)

  if [[ "$OLD_CODE" =~ ^[0-9]+$ ]]; then
    if (( OLD_CODE > VERSION_CODE )); then
      echo "A newer $CHANNEL manifest is already published; keeping it."
      exit 0
    fi
    if (( OLD_CODE == VERSION_CODE )) &&
       [[ "$(jq -r '.apkUrl // empty' <<<"$CURRENT_JSON" 2>/dev/null || true)" == "$APK_URL" ]]; then
      echo "The $CHANNEL feed already points to this public APK; nothing to update."
      exit 0
    fi
  fi

  ARGS=(--method PUT "repos/$PUBLIC_REPO/contents/$MANIFEST"
    -f "message=Publish public $APP_LABEL $VERSION ($CHANNEL) APK feed"
    -f "content=$CONTENT" -f "branch=main")
  [[ -z "$CURRENT_SHA" ]] || ARGS+=(-f "sha=$CURRENT_SHA")
  if gh api "${ARGS[@]}" >/dev/null; then
    echo "Published $CHANNEL feed for $APP_LABEL using $APK_URL"
    exit 0
  fi

  [[ "$attempt" -lt 5 ]] || { echo "::error::Could not update $MANIFEST after five attempts" >&2; exit 1; }
  sleep "$((attempt * 2))"
done
