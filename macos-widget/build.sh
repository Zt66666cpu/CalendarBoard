#!/bin/zsh
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$DIR/CalendarBoard.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/ui"

echo "Compiling CalendarBoard.app..."
swiftc -O -swift-version 5 \
  -framework AppKit \
  -framework WebKit \
  -framework QuartzCore \
  "$DIR/Sources/main.swift" \
  -o "$APP/Contents/MacOS/CalendarBoard"

cp "$DIR/Info.plist" "$APP/Contents/Info.plist"
cp -R "$DIR/ui/." "$APP/Contents/Resources/ui/"

codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true

echo "Built successfully: $APP"
