#!/usr/bin/env sh

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS_FILE="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$PROPS_FILE" ]; then
  echo "Missing $PROPS_FILE" >&2
  exit 1
fi

DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS_FILE" | sed 's#\\:#:#g')
if [ -z "$DIST_URL" ]; then
  echo "distributionUrl is not defined" >&2
  exit 1
fi

DIST_FILE=$(basename "$DIST_URL")
DIST_NAME=$(echo "$DIST_FILE" | sed 's/\.zip$//')
DIST_DIR_NAME=$(echo "$DIST_FILE" | sed 's/-bin\.zip$//' | sed 's/-all\.zip$//' | sed 's/\.zip$//')
if [ -z "${GRADLE_USER_HOME:-}" ]; then
  GRADLE_USER_HOME="$APP_HOME/.gradle"
  export GRADLE_USER_HOME
fi
LOCAL_GRADLE_HOME="$GRADLE_USER_HOME"
CACHE_DIR="$LOCAL_GRADLE_HOME/wrapper/dists/$DIST_NAME"
ZIP_PATH="$CACHE_DIR/$DIST_FILE"

mkdir -p "$CACHE_DIR"

if [ ! -x "$CACHE_DIR/$DIST_DIR_NAME/bin/gradle" ]; then
  if [ ! -f "$ZIP_PATH" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$DIST_URL" -o "$ZIP_PATH"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_PATH" "$DIST_URL"
    else
      echo "curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi

  rm -rf "$CACHE_DIR/$DIST_DIR_NAME"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_PATH" -d "$CACHE_DIR"
  else
    echo "unzip is required to extract $ZIP_PATH" >&2
    exit 1
  fi
fi

exec "$CACHE_DIR/$DIST_DIR_NAME/bin/gradle" "$@"
