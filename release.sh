#!/usr/bin/env bash
# Выпуск новой версии: ./release.sh 1.1 "Что нового"
# Поднимает versionCode, собирает и подписывает APK, выкладывает его в GitHub Releases
# и обновляет android.json на сайте — приложения на телефонах сами предложат обновиться.
set -euo pipefail
NAME="${1:?версия, например 1.1}"; NOTES="${2:-}"
ROOT="$(cd "$(dirname "$0")" && pwd)"; TOOLS="G:/posudomoyka-android"
SITE="G:/04 claude code/яйца клауд"; REPO="fggtf24-cyber/yaytsa-cloud-android"
GRADLE="$ROOT/app/build.gradle.kts"

CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+')
if ! grep -q "versionName = \"$NAME\"" "$GRADLE"; then
  CODE=$((CODE + 1))
  sed -i "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$NAME\"/" "$GRADLE"
fi
echo "Версия $NAME (код $CODE)"

cd "$ROOT"
JAVA_HOME="$TOOLS/jdk" env -u NoDefaultCurrentDirectoryInExePath cmd //c "gradlew.bat assembleRelease --console=plain -q"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
"$TOOLS/sdk/build-tools/36.1.0/apksigner.bat" verify "$APK"
cp "$APK" "$ROOT/YaytsaCloud.apk"
git add -A && git commit -q -m "Android $NAME" || true
git push -q origin main

gh release create "v$NAME" "$ROOT/YaytsaCloud.apk" --repo "$REPO" --title "Яйца Cloud $NAME" --notes "${NOTES:-Новая версия}"
URL="https://github.com/$REPO/releases/download/v$NAME/YaytsaCloud.apk"

python - "$SITE/android.json" "$CODE" "$NAME" "$URL" "$NOTES" <<'PY'
import json, sys
p, code, name, url, notes = sys.argv[1:6]
json.dump({"versionCode": int(code), "versionName": name, "url": url, "notes": notes},
          open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
cd "$SITE"
git add android.json
git commit -q -m "Android $NAME"
git pull -q --rebase && git push -q origin main
echo "Готово: $URL"
