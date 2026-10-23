#!/usr/bin/env bash
# Runs ./gradlew <args> in a memory-capped container (the fleet's own cgroup is too small for ModDevGradle + a test
# server). Uses the host's ~/.gradle caches and JDK 21 (NeoForge 1.21.1). One at a time: the fixed container name
# makes a second concurrent run fail instead of doubling the memory use.
#   tools/gradle-docker.sh build              -> build/libs/localchainsaws-1.21.1-<version>.jar
#   tools/gradle-docker.sh runGameTestServer  -> every gametest, non-zero exit if one fails
set -euo pipefail
cd "$(dirname "$0")/.."
JDK=$HOME/opt/jdk-21.0.12.1+1
NAME=localchainsaws-nf121-gradle
trap 'docker rm -f $NAME >/dev/null 2>&1 || true' EXIT
docker run --rm --name $NAME --memory 2600m --cpus 3 --user "$(id -u):$(id -g)" \
  -e HOME=/home/u -e GRADLE_USER_HOME=/home/u/.gradle -e JAVA_HOME=/jdk -e JAVA_TOOL_OPTIONS=-Xmx1100m \
  -v "$HOME/.gradle:/home/u/.gradle" -v "$JDK:/jdk:ro" -v "$PWD:/src" -w /src eclipse-temurin:25-jre \
  ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx900m "$@"
