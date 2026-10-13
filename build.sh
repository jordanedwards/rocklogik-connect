#!/usr/bin/env bash
# Build, test or lint RockLogik Connect in Docker, so the result does not depend on whose
# machine ran it. Usage: ./build.sh apk|test|lint|all
set -euo pipefail
cd "$(dirname "$0")"
image=rocklogik-connect-build
docker build -q -t "$image" . > /dev/null
run() {
  mkdir -p "$HOME/.cache/rocklogik-connect-gradle"
  docker run --rm -v "$PWD":/src -v "$HOME/.cache/rocklogik-connect-gradle":/gradle -u "$(id -u):$(id -g)" \
    -e GRADLE_USER_HOME=/gradle -e ANDROID_USER_HOME=/gradle/android "$image" \
    ./gradlew --console=plain --no-daemon "$@"
}
case "${1:-all}" in
  apk)  run :app:assembleDebug ;;
  test) run :app:testDebugUnitTest ;;
  lint) run :app:lintDebug ;;
  all)  run :app:lintDebug :app:testDebugUnitTest :app:assembleDebug ;;
  *)    echo "usage: $0 apk|test|lint|all" >&2; exit 2 ;;
esac
