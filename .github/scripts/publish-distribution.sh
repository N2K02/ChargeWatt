#!/usr/bin/env bash
# Publish only the signed APK to the public WEBSITE repository.
# Called by a successful private-source workflow with a narrowly scoped GitHub token.
set -euo pipefail

PUBLIC_REPO="N2K02/n2k02.github.io"
: "${GH_TOKEN:?CHARGEWATT_DISTRIBUTION_TOKEN must be configured}"
: "${APK_NAME:?Missing APK_NAME}"
: "${VERSION:?Missing VERSION}"
: "${VERSION_CODE:?Missing VERSION_CODE}"
: "${GITHUB_RUN_NUMBER:?Missing run number}"

APK="$APK_NAME.apk"
test -s "$APK"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c '%s' "$APK")

if [[ "${GITHUB_EVENT_NAME}" == "pull_request" ]]; then
  CHANNEL="experimental"
  TAG="chargewatt-exp-pr-${PR_NUMBER:?}-run-${GITHUB_RUN_NUMBER}"
  NAME="${VERSION}-experimental.${GITHUB_RUN_NUMBER}"
  TITLE="chargeWatt experimental · PR #${PR_NUMBER} · build #${GITHUB_RUN_NUMBER}"
  GH_FLAGS=(--prerelease --latest=false)
  NOTES="$(cat release-notes.md)"
else
  CHANNEL="stable"
  TAG="chargewatt-v${VERSION}-build-${GITHUB_RUN_NUMBER}"
  NAME="${VERSION}"
  TITLE="chargeWatt ${VERSION}"
  GH_FLAGS=(--latest)
  NOTES="$(cat release-notes.md)"
fi

# A public *website* repository contains no Android source files.
# The release asset is a directly downloadable .apk, not an Actions ZIP.
if ! gh release view "$TAG" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
  gh release create "$TAG" "$APK" --repo "$PUBLIC_REPO" --target main \
    --title "$TITLE" --notes "$NOTES" "${GH_FLAGS[@]}"
else
  gh release upload "$TAG" "$APK" --repo "$PUBLIC_REPO" --clobber
fi

URL="https://github.com/$PUBLIC_REPO/releases/download/$TAG/$APK"
PAGE_URL="https://github.com/$PUBLIC_REPO/releases/tag/$TAG"
NOTES_JSON=$(grep '^- ' release-notes.md | head -30 | sed 's/^- //' | jq -R -s 'split("\n") | map(select(length > 0))')
jq -n \
  --arg channel "$CHANNEL" \
  --arg tag "$TAG" \
  --arg versionName "$NAME" \
  --argjson versionCode "$VERSION_CODE" \
  --arg apkName "$APK" \
  --arg apkUrl "$URL" \
  --argjson apkSize "$SIZE" \
  --arg sha256 "$SHA" \
  --arg pageUrl "$PAGE_URL" \
  --argjson notes "$NOTES_JSON" \
  '{channel:$channel,tag:$tag,versionName:$versionName,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:$notes}' > update-manifest.json

PATH_IN_REPO="chargewatt/updates/$CHANNEL.json"
# Prevent an older parallel workflow from replacing a newer channel manifest.
OLD=$(gh api "repos/$PUBLIC_REPO/contents/$PATH_IN_REPO" --jq '.content' 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [[ -n "$OLD" ]]; then
  PREVIOUS_CODE=$(jq -r '.versionCode // 0' <<< "$OLD")
  if [[ "$PREVIOUS_CODE" =~ ^[0-9]+$ ]] && (( PREVIOUS_CODE >= VERSION_CODE )); then
    echo "Distribution manifest is already at versionCode $PREVIOUS_CODE; leaving it unchanged"
    exit 0
  fi
fi

FEED_REPO="${PUBLIC_REPO:-${TARGET:-}}"
FEED_PATH="${PATH_IN_REPO:-${MANIFEST:-}}"
FEED_SLUG="${FEED_PATH%%/*}"
FEED_BRANCH="publish-feed-${FEED_SLUG}-${CHANNEL}"
APP_LABEL="${APP:-${APK_NAME:-$FEED_SLUG}}"
[[ -n "$FEED_REPO" && -n "$FEED_PATH" ]] || { echo "::error::Missing public feed target" >&2; exit 1; }

# Keep one reviewable feed PR per app and channel. Never write the protected main branch.
if ! gh api "repos/$FEED_REPO/git/ref/heads/$FEED_BRANCH" >/dev/null 2>&1; then
  MAIN_SHA=$(gh api "repos/$FEED_REPO/git/ref/heads/main" --jq '.object.sha')
  gh api --method POST "repos/$FEED_REPO/git/refs" \
    -f "ref=refs/heads/$FEED_BRANCH" -f "sha=$MAIN_SHA" >/dev/null || {
      # A concurrent publisher may have created the predictable branch first.
      gh api "repos/$FEED_REPO/git/ref/heads/$FEED_BRANCH" >/dev/null
    }
fi

# A newer run may already have updated the open feed branch.
CURRENT_JSON=$(gh api "repos/$FEED_REPO/contents/$FEED_PATH?ref=$FEED_BRANCH" --jq '.content' 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [[ -n "$CURRENT_JSON" ]]; then
  BRANCH_CODE=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
  if [[ "$BRANCH_CODE" =~ ^[0-9]+$ ]] && (( BRANCH_CODE >= VERSION_CODE )); then
    echo "Equal or newer feed manifest is already on $FEED_BRANCH; keeping it."
    exit 0
  fi
fi

for attempt in 1 2 3 4 5; do
  CURRENT_JSON=$(gh api "repos/$FEED_REPO/contents/$FEED_PATH?ref=$FEED_BRANCH" --jq '.content' 2>/dev/null | tr -d '\\n' | base64 -d 2>/dev/null || true)
  if [[ -n "$CURRENT_JSON" ]]; then
    BRANCH_CODE=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
    if [[ "$BRANCH_CODE" =~ ^[0-9]+$ ]] && (( BRANCH_CODE >= VERSION_CODE )); then
      echo "Equal or newer feed manifest is already on $FEED_BRANCH; keeping it."
      exit 0
    fi
  fi
  CURRENT_SHA=$(gh api "repos/$FEED_REPO/contents/$FEED_PATH?ref=$FEED_BRANCH" --jq '.sha' 2>/dev/null || true)
  ARGS=(--method PUT "repos/$FEED_REPO/contents/$FEED_PATH" \
    -f "message=Prepare $APP_LABEL $VERSION ($CHANNEL) update feed" \
    -f "content=$(base64 -w0 update-manifest.json)" -f "branch=$FEED_BRANCH")
  [[ -z "$CURRENT_SHA" ]] || ARGS+=(-f "sha=$CURRENT_SHA")
  if gh api "${ARGS[@]}" >/dev/null; then
    break
  fi
  [[ "$attempt" -lt 5 ]] || { echo "::error::Unable to update feed branch after retries" >&2; exit 1; }
  sleep "$((attempt * 2))"
done

EXISTING_PR=$(gh pr list --repo "$FEED_REPO" --state open --head "N2K02:$FEED_BRANCH" --json number --jq '.[0].number // empty')
if [[ -z "$EXISTING_PR" ]]; then
  gh pr create --repo "$FEED_REPO" --base main --head "$FEED_BRANCH" \
    --title "Publish $APP_LABEL $VERSION ($CHANNEL) update feed" \
    --body "Updates `$FEED_PATH` to reference the signed APK already published for $APP_LABEL $VERSION ($CHANNEL). The app will discover this release after the feed change is merged."
  echo "Created feed pull request for $APP_LABEL $CHANNEL."
else
  echo "Updated feed pull request #$EXISTING_PR for $APP_LABEL $CHANNEL."
fi
