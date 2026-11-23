#!/bin/bash
# Script untuk accept semua Android SDK license

SDK_ROOT="/c/Android/Sdk"
CMD_TOOLS="/c/Android/Sdk/cmdline-tools/cmdline-tools/bin/sdkmanager.bat"

cd /c/Android/Sdk/cmdline-tools

# Accept semua license
echo "=== Accepting all licenses ==="
for license in "android-sdk-license" "android-sdk-preview-license" "google-gdk-license" "android-googletv-license" "android-googlexr-license" "mips-android-sysimage-license" "intel-android-sysimage-license" "android-wear-os-preview-license"; do
    echo "y" | "$CMD_TOOLS" --sdk_root="$SDK_ROOT" "--licenses" 2>&1 | grep -q "accepted" && echo "✓ $license accepted" || echo "✗ $license failed"
done

echo "=== Installing required packages ==="
"$CMD_TOOLS" --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1

echo "=== Done ==="
ls -la "$SDK_ROOT/platforms/" 2>/dev/null || echo "No platforms installed"
ls -la "$SDK_ROOT/build-tools/" 2>/dev/null || echo "No build-tools installed"
