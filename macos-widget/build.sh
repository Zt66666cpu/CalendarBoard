#!/bin/zsh
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

# The acceptance harness invokes this script with bash; re-exec under zsh so the
# zsh-specific null-glob qualifier below behaves as intended.
if [ -z "${ZSH_VERSION:-}" ]; then
  exec /bin/zsh "$DIR/build.sh" "$@"
fi

APP="$DIR/CalendarBoard.app"

# Always remove the previous app bundle first so whatever is produced below is
# guaranteed to come from this build, never a stale artifact.
rm -rf "$APP"

# Command Line Tools only (no Xcode.app) lack the SwiftUIMacros compiler plugin
# required by the macOS 27 SDK's external `@State`/`#Preview` macros. Fall back
# to the 26.5 SDK, where those declarations compile with CLT alone.
if [[ -z "${SDKROOT:-}" ]] \
  && [[ "$(xcode-select -p 2>/dev/null)" == "/Library/Developer/CommandLineTools" ]] \
  && [[ ! -e "/Library/Developer/CommandLineTools/usr/lib/swift/host/libSwiftUIMacros.dylib" ]] \
  && [[ -d "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk" ]]; then
  export SDKROOT="/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"
  echo "CLT-only toolchain detected (no libSwiftUIMacros.dylib); using SDKROOT=$SDKROOT"
fi

# Keep SwiftPM's temporary files and Clang module cache inside the package so
# the build also works in sandboxed environments with a read-only HOME.
export TMPDIR="$DIR/.build/tmp"
export CLANG_MODULE_CACHE_PATH="$DIR/.build/clang-module-cache"
mkdir -p "$TMPDIR" "$CLANG_MODULE_CACHE_PATH" "$DIR/.build/swiftpm-cache"

echo "Building CalendarBoard via SwiftPM (release)..."
(cd "$DIR" && swift build -c release --disable-sandbox \
  --cache-path "$DIR/.build/swiftpm-cache" \
  --scratch-path "$DIR/.build")

BIN="$DIR/.build/release/CalendarBoard"
if [[ ! -f "$BIN" ]]; then
  echo "Error: compiled binary not found at $BIN" >&2
  exit 1
fi

mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/ui"

cp "$BIN" "$APP/Contents/MacOS/CalendarBoard"
cp "$DIR/Info.plist" "$APP/Contents/Info.plist"
cp "$DIR/../web/core.js" "$DIR/ui/core.js"
cp -R "$DIR/ui/." "$APP/Contents/Resources/ui/"

# Copy SwiftPM resource bundles (e.g. KeyboardShortcuts localization) into Contents/Resources/
for bundle in "$DIR"/.build/release/*.bundle(N); do
  cp -R "$bundle" "$APP/Contents/Resources/"
done

codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true

echo "Built successfully: $APP"
