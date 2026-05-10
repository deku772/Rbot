#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_BUILD_GRADLE="$ROOT_DIR/app/build.gradle"
TAG_PREFIX="v"
REMOTE="origin"
ASK_CONFIRM=true
DRY_RUN=false
RELEASE_DRAFT=false
PROMOTE_RELEASE=false
OVERWRITE_DRAFT=false
DRAFT_TAG_MARKER="[botdrop:draft]"

SEMVER_REGEX='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(\.(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?(\+([0-9a-zA-Z-]+(\.[0-9a-zA-Z-]+)*))?$'

usage() {
  cat <<EOF
Usage:
  $SCRIPT_NAME <version> [--version-code <number>] [--remote <name>] [--draft] [--overwrite] [--promote] [--no-confirm] [--dry-run]

Examples:
  $SCRIPT_NAME 0.2.7
  $SCRIPT_NAME 0.2.7 --draft
  $SCRIPT_NAME 0.2.7 --remote origin --no-confirm
  $SCRIPT_NAME 0.2.7 --version-code 120
  $SCRIPT_NAME 0.2.3 --promote
  $SCRIPT_NAME 0.2.4 --draft --overwrite

Description:
  Updates app/build.gradle versionName and increments versionCode, creates a git
  commit, tags as v<version>, and pushes HEAD + tag to remote.

  Add --draft to mark the tag for draft release creation (tag name remains
  unchanged, e.g., v1.2.3). Use --promote to publish an existing draft
  release.
  Add --overwrite to recreate an existing draft release/tag for the same version.

  If versionName is unchanged, versionCode is unchanged by default.
  If versionName changes, versionCode is automatically incremented by 1.
  Override versionCode explicitly with --version-code.
EOF
}

need_arg() {
  if [[ -z "${1:-}" ]]; then
    echo "Missing argument for: $2" >&2
    usage
    exit 1
  fi
}

check_workspace_clean() {
  if [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
    echo "Working tree is not clean. Commit or stash your changes first." >&2
    exit 1
  fi
}

validate_version() {
  local version=$1
  if ! printf '%s' "$version" | grep -Eq "$SEMVER_REGEX"; then
    echo "Invalid version '$version'. Expected semver like 0.2.7 or 1.2.3-rc.1+build.1" >&2
    exit 1
  fi
}

resolve_repo() {
  local remote_url
  remote_url=$(git -C "$ROOT_DIR" remote get-url "$REMOTE")
  case "$remote_url" in
    git@github.com:*)
      remote_url="${remote_url#git@github.com:}"
      ;;
    https://github.com/*)
      remote_url="${remote_url#https://github.com/}"
      ;;
    http://github.com/*)
      remote_url="${remote_url#http://github.com/}"
      ;;
    *)
      echo "Unsupported remote URL: $remote_url" >&2
      exit 1
      ;;
  esac
  remote_url="${remote_url%.git}"
  echo "$remote_url"
}

ensure_github_token() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    return 0
  fi

  if [[ -n "${GH_TOKEN:-}" ]]; then
    return 0
  fi

  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI is required for --promote or --overwrite when GITHUB_TOKEN/GH_TOKEN is not set." >&2
    exit 1
  fi

  local resolved_token
  resolved_token="$(gh auth token 2>/dev/null || true)"
  if [[ -z "$resolved_token" ]]; then
    echo "GITHUB_TOKEN or GH_TOKEN is required for --promote or --overwrite. Authenticate with 'gh auth login' first." >&2
    exit 1
  fi

  GH_TOKEN="$resolved_token"
}

run_gh() {
  if $DRY_RUN; then
    echo "[dry-run] gh $*"
    return 0
  fi

  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    GH_TOKEN="${GITHUB_TOKEN}" gh "$@"
  else
    GH_TOKEN="${GH_TOKEN}" gh "$@"
  fi
}

prepare_existing_draft_release() {
  local tag=$1
  local repo
  repo="$(resolve_repo)"

  local local_tag_exists=false
  local remote_tag_exists=false

  if git -C "$ROOT_DIR" rev-parse "$tag" >/dev/null 2>&1; then
    local_tag_exists=true
  fi
  if git -C "$ROOT_DIR" ls-remote --exit-code --tags "$REMOTE" "refs/tags/$tag" >/dev/null 2>&1; then
    remote_tag_exists=true
  fi

  if [[ "$local_tag_exists" == false && "$remote_tag_exists" == false ]]; then
    return 0
  fi

  echo "Existing tag detected for $tag."

  if ! $OVERWRITE_DRAFT; then
    echo "Tag $tag already exists. Use --draft --overwrite to recreate." >&2
    return 0
  fi

  if ! $RELEASE_DRAFT; then
    echo "Refuse to overwrite non-draft release. Use --draft with --overwrite." >&2
    exit 1
  fi

  ensure_github_token
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI is required for --overwrite (tag: $tag, repo: $repo). Install github.com/cli/cli and authenticate." >&2
    exit 1
  fi
  confirm "Overwrite existing draft release and tag $tag?"

  if $DRY_RUN; then
    if [[ "$local_tag_exists" == true ]]; then
      run_cmd "git -C '$ROOT_DIR' tag -d '$tag'"
    fi
    if [[ "$remote_tag_exists" == true ]]; then
      run_cmd "git -C '$ROOT_DIR' push '$REMOTE' :$tag"
    fi
    return 0
  fi

  local release_is_draft=""
  set +e
  release_is_draft="$(run_gh release view "$tag" --repo "$repo" --json isDraft --jq '.isDraft' 2>/dev/null)"
  local release_view_rc=$?
  set -e

  if [[ $release_view_rc -eq 0 ]]; then
    if [[ "$release_is_draft" != "true" ]]; then
      echo "Existing non-draft release for $tag found. --overwrite only supports draft release." >&2
      exit 1
    fi
    run_gh release delete "$tag" --repo "$repo" --yes
  fi

  if [[ "$local_tag_exists" == true ]]; then
    run_cmd "git -C '$ROOT_DIR' tag -d '$tag'"
  fi
  if [[ "$remote_tag_exists" == true ]]; then
    run_cmd "git -C '$ROOT_DIR' push '$REMOTE' :$tag"
  fi
}

promote_release() {
  local tag=$1
  local repo
  repo=$(resolve_repo)
  local token_message="GH_TOKEN"
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    token_message="GITHUB_TOKEN"
  fi

  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI is required for --promote (tag: $tag, repo: $repo). Install github.com/cli/cli and authenticate." >&2
    exit 1
  fi

  if [[ "$DRY_RUN" == true ]]; then
    echo "[dry-run] Would promote release $tag in $repo via gh release edit --draft=false"
    return
  fi

  echo "Publishing draft release $tag to regular release using $token_message..."
  ensure_github_token
  run_gh release edit "$tag" --repo "$repo" --draft=false --prerelease=false
}

read_current_version() {
  local version
  version=$(grep -m 1 -E "^[[:space:]]*versionName[[:space:]]+\"[^\"]+\"$" "$APP_BUILD_GRADLE" | sed -E 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*$/\1/' || true)
  if [[ -z "$version" ]]; then
    echo "Failed to find versionName in $APP_BUILD_GRADLE" >&2
    exit 1
  fi
  echo "$version"
}

read_current_version_code() {
  local version_code
  version_code=$(grep -m 1 -E "^[[:space:]]*versionCode[[:space:]]+[0-9]+$" "$APP_BUILD_GRADLE" | sed -E 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*$/\1/' || true)
  if [[ -z "$version_code" ]]; then
    echo "Failed to find versionCode in $APP_BUILD_GRADLE" >&2
    exit 1
  fi
  echo "$version_code"
}

set_versions() {
  local new_version=$1
  local new_version_code=$2
  if $DRY_RUN; then
    echo "[dry-run] Would update $APP_BUILD_GRADLE:"
    echo "  versionName -> \"$new_version\""
    echo "  versionCode -> $new_version_code"
    return 0
  fi

  local tmp_file
  tmp_file="$(mktemp)"
  while IFS= read -r line; do
    if [[ "$line" =~ ^([[:space:]]*)versionName[[:space:]]+\" ]]; then
      line="${BASH_REMATCH[1]}versionName \"${new_version}\""
    elif [[ "$line" =~ ^([[:space:]]*)versionCode[[:space:]]+[0-9]+$ ]]; then
      line="${BASH_REMATCH[1]}versionCode ${new_version_code}"
    fi
    echo "$line" >> "$tmp_file"
  done < "$APP_BUILD_GRADLE"
  mv "$tmp_file" "$APP_BUILD_GRADLE"
}

check_git_state() {
  local branch
  branch=$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)
  if [[ "$branch" == "HEAD" ]]; then
    echo "Refusing to run on detached HEAD." >&2
    exit 1
  fi
}

confirm() {
  local message=$1
  local answer
  if ! $ASK_CONFIRM; then
    return 0
  fi

  read -r -p "$message [y/N] " answer
  if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "Aborted by user."
    exit 0
  fi
}

confirm_push() {
  local message=$1
  local answer
  read -r -p "$message [y/N] " answer
  if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "Push canceled by user."
    exit 0
  fi
}

run_cmd() {
  local cmd="$*"
  if $DRY_RUN; then
    echo "[dry-run] $cmd"
  else
    eval "$cmd"
  fi
}

main() {
  if [[ $# -lt 1 ]]; then
    usage
    exit 1
  fi

  local version=""
  local explicit_version_code=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --version-code)
        shift
        need_arg "${1:-}" "--version-code"
        explicit_version_code="$1"
        if ! [[ "$explicit_version_code" =~ ^[0-9]+$ ]]; then
          echo "Invalid version code '$explicit_version_code'. Expected a non-negative integer." >&2
          exit 1
        fi
        ;;
      --remote)
        shift
        need_arg "${1:-}" "--remote"
        REMOTE="$1"
        ;;
      --draft)
        RELEASE_DRAFT=true
        ;;
      --overwrite)
        OVERWRITE_DRAFT=true
        ;;
      --promote)
        if $PROMOTE_RELEASE; then
          echo "Cannot specify --promote more than once." >&2
          exit 1
        fi
        PROMOTE_RELEASE=true
        ;;
      --no-confirm)
        ASK_CONFIRM=false
        ;;
      --dry-run)
        DRY_RUN=true
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        if [[ -n "$version" ]]; then
          echo "Unexpected extra argument: $1" >&2
          usage
          exit 1
        fi
        version="$1"
        ;;
    esac
    shift
  done

  if [[ -z "$version" ]]; then
    echo "Version is required."
    usage
    exit 1
  fi

  validate_version "$version"

  cd "$ROOT_DIR"

  local tag="$TAG_PREFIX$version"

  if $OVERWRITE_DRAFT && $PROMOTE_RELEASE; then
    echo "Cannot combine --overwrite and --promote." >&2
    exit 1
  fi

  if $PROMOTE_RELEASE; then
    confirm "Promote release draft $tag to public release?"
    promote_release "$tag"
    echo "Done."
    exit 0
  fi

  if [[ ! -f "$APP_BUILD_GRADLE" ]]; then
    echo "Cannot find $APP_BUILD_GRADLE" >&2
    exit 1
  fi

  check_git_state
  check_workspace_clean
  prepare_existing_draft_release "$tag"

  local current_version
  current_version=$(read_current_version)
  local current_version_code
  current_version_code=$(read_current_version_code)
  local next_version_code=""

  if [[ -n "$explicit_version_code" ]]; then
    next_version_code="$explicit_version_code"
  elif [[ "$version" != "$current_version" ]]; then
    next_version_code=$((current_version_code + 1))
  else
    next_version_code="$current_version_code"
  fi

  local needs_version_update=false
  if [[ "$version" != "$current_version" || "$next_version_code" != "$current_version_code" ]]; then
    needs_version_update=true
  fi

  if ! $OVERWRITE_DRAFT && git -C "$ROOT_DIR" rev-parse "$tag" >/dev/null 2>&1; then
    echo "Tag $tag already exists. Choose another version or rerun with --overwrite." >&2
    exit 1
  fi

  local remote_tag_exists=false
  if git -C "$ROOT_DIR" ls-remote --exit-code --tags "$REMOTE" "refs/tags/$tag" >/dev/null 2>&1; then
    remote_tag_exists=true
  fi
  if ! $OVERWRITE_DRAFT && [[ "$remote_tag_exists" == true ]]; then
    echo "Tag $tag already exists on remote '$REMOTE'. Choose another version or rerun with --overwrite." >&2
    exit 1
  fi

  echo "Current versionName: $current_version"
  echo "Current versionCode: $current_version_code"
  echo "New versionName:     $version"
  echo "New versionCode:     $next_version_code"
  if [[ -z "$explicit_version_code" && "$version" == "$current_version" ]]; then
    echo "VersionCode strategy: unchanged (versionName unchanged)"
  elif [[ -z "$explicit_version_code" && "$version" != "$current_version" ]]; then
    echo "VersionCode strategy: auto-increment because versionName changed"
  else
    echo "VersionCode strategy: explicit --version-code"
  fi
  echo "Release tag:         $tag"
  echo "Remote:              $REMOTE"
  local tag_message="Release $tag"
  if $RELEASE_DRAFT; then
    tag_message="$tag_message $DRAFT_TAG_MARKER"
  fi

  confirm "Proceed with updating, committing and tagging $tag?"

  if $needs_version_update; then
    echo "Updating $APP_BUILD_GRADLE"
    set_versions "$version" "$next_version_code"
    if $DRY_RUN; then
      echo "Updated versionName: (dry-run) $version"
      echo "Updated versionCode: (dry-run) $next_version_code"
    else
      run_cmd "git -C '$ROOT_DIR' add app/build.gradle"
      if ! git -C "$ROOT_DIR" diff --cached --quiet -- app/build.gradle; then
        run_cmd "git -C '$ROOT_DIR' commit -m 'chore: release $tag'"
      else
        echo "No staged changes detected; skipping commit."
      fi
      run_cmd "git -C '$ROOT_DIR' tag -a '$tag' -m \"$tag_message\""
    fi
  else
    echo "No version field changes needed; skipping app/build.gradle update."
    if ! $DRY_RUN; then
      run_cmd "git -C '$ROOT_DIR' tag -a '$tag' -m \"$tag_message\""
    else
      echo "Release action: (dry-run) would create tag '$tag' for HEAD."
    fi
  fi

  if ! $DRY_RUN; then
    confirm_push "Push commit and tag $tag to '$REMOTE' now?"
    run_cmd "git -C '$ROOT_DIR' push '$REMOTE' HEAD '$tag'"
  else
    echo "[dry-run] git -C '$ROOT_DIR' push '$REMOTE' HEAD '$tag'"
  fi

  echo "Done."
}

main "$@"
