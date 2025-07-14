variant="$1"
if [ -z "$variant" ]; then
  variant="debug"
fi
adb install "app/build/outputs/apk/$variant/app-$variant.apk"
