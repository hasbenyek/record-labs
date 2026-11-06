#!/usr/bin/env bash
# Builds Record-Labs-<version>.ipa (unsigned, for Sideloadly) on a Mac:
#   1. Gradle builds the shared app as RecordLabsShared.framework (release, iPhone);
#   2. XcodeGen writes the Xcode project, xcodebuild builds the app without signing;
#   3. the .app goes into Payload/ and is zipped as the .ipa.
# Run from the repository root: ./gradlew :shared:buildIosIpa -Precordlabs.ios=true
# (which runs this script), or directly: iosApp/build-ipa.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/^val appVersionName = "\(.*\)"/\1/p' androidApp/build.gradle.kts | head -1)"
VERSION="${VERSION:-0.0.0}"
OUT="$ROOT/build/ios"
mkdir -p "$OUT"

if [ "${SKIP_GRADLE:-}" != "1" ]; then
  ./gradlew --no-daemon :shared:linkReleaseFrameworkIosArm64 -Precordlabs.ios=true
fi

command -v xcodegen >/dev/null || { echo "xcodegen is missing (brew install xcodegen)"; exit 1; }
cd "$ROOT/iosApp"
sed -i '' "s/MARKETING_VERSION: \".*\"/MARKETING_VERSION: \"$VERSION\"/" project.yml
xcodegen generate
xcodebuild \
  -project RecordLabs.xcodeproj \
  -scheme RecordLabs \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$OUT/DerivedData" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  build

APP="$OUT/DerivedData/Build/Products/Release-iphoneos/RecordLabs.app"
test -d "$APP" || { echo "No app was built"; exit 1; }
rm -rf "$OUT/Payload" && mkdir -p "$OUT/Payload"
cp -R "$APP" "$OUT/Payload/"
IPA="$OUT/Record-Labs-$VERSION.ipa"
rm -f "$IPA"
(cd "$OUT" && zip -qry "$IPA" Payload)
rm -rf "$OUT/Payload"
echo "Built $IPA"
