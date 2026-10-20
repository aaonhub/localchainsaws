#!/usr/bin/env bash
# Full-modpack check of a new chainsaw jar (and the deathcoords datapack) on northstar, in a memory-capped container:
# The Local's published host kit -> Forge install + pack sync -> boot once (fresh world, datapack loaded), then
#   selftest  : swap in the new jar, start with -Dlocalchainsaws.selftest=true (runs every scenario, then stops)
#   reload    : start again, edit the chainsaw server config, /mfsrc (ModernFix: Forge's file watcher is off), wait
#               for "server config changed" in the log
# Usage: [STEPS="boot1 selftest reload"] tools/fullpack-test.sh <new jar> [workdir=/tmp/lcs-fullpack]
# Throwaway only: never point it at a live server. The kit's watchdog is turned off (max-tick-time=-1): SelfTest builds
# its arenas far out in fresh chunks in one tick, which takes over 60 s with the whole pack's worldgen on a busy box.
# NEVER run a second Minecraft JVM on northstar outside a container with a hard memory cap (incident 2026-10-11).
set -euo pipefail
JAR=$(realpath "$1"); W=${2:-/tmp/lcs-fullpack}
KIT_URL=https://bdoyl.com/local/minecraft/files/The-Local-Server.zip
DATAPACK=$HOME/game-servers/minecraft-kit/datapacks/deathcoords.zip
NAME=lcs-fullpack; IMG=eclipse-temurin:25-jre; JDK=$HOME/opt/jdk-17.0.20.1+1
log() { printf '\n== %s %s\n' "$(date +%H:%M:%S)" "$*"; }

run() { # run <seconds> <stop regex> <env...>: start the kit's start.sh, wait for the regex or the container to exit
  local secs=$1 re=$2; shift 2
  docker rm -f $NAME >/dev/null 2>&1 || true
  docker run -d --name $NAME --memory 2900m --cpus 3 --user "$(id -u):$(id -g)" -e HOME=/tmp -e EULA=true -e MEMORY=2200M \
    "${@/#/-e}" -v "$W/kit:/srv" -v "$JDK:/jdk:ro" -w /srv $IMG \
    sh -c ': > console.txt; export PATH=/jdk/bin:$PATH; tail -f -n0 console.txt | sh start.sh' >/dev/null
  timeout "$secs" bash -c "until docker logs $NAME 2>&1 | grep -qE '$re' || [ \"\$(docker inspect -f '{{.State.Running}}' $NAME)\" != true ]; do sleep 5; done" || true
}
say() { echo "$*" >> "$W/kit/console.txt"; } # a line typed into the server console
stop() { docker stop -t 90 $NAME >/dev/null 2>&1 || true; docker logs $NAME > "$W/$1.log" 2>&1 || true; docker rm -f $NAME >/dev/null 2>&1 || true; }

mkdir -p "$W"; cd "$W"
if [ ! -d kit ]; then
  log "download + unpack the host kit"
  curl -fsSL -o kit.zip "$KIT_URL"; mkdir kit; (cd kit && unzip -q ../kit.zip); rm kit.zip
  [ -f kit/start.sh ] || { d=$(dirname "$(find kit -name start.sh | head -1)"); mv "$d"/* kit/; }
fi
mkdir -p kit/world/datapacks; cp "$DATAPACK" kit/world/datapacks/deathcoords.zip
sed -i 's/^max-tick-time=.*/max-tick-time=-1/' kit/server.properties
STEPS=${STEPS:-boot1 selftest reload}

if [[ " $STEPS " == *" boot1 "* ]]; then
log "boot 1: Forge install + pack sync + fresh world"
run 900 'Done \(|Exception in server tick loop|Crash report'
stop boot1
grep -E 'Done \(|Failed to load function|deathcoords|Unknown or incomplete command' boot1.log | head -20 || true
fi

if [[ " $STEPS " == *" selftest "* ]]; then
log "boot 2: new jar + selftest"
rm -f kit/mods/localchainsaws-*.jar; cp "$JAR" kit/mods/
grep -q localchainsaws.selftest kit/jvm-flags.txt || echo '-Dlocalchainsaws.selftest=true' >> kit/jvm-flags.txt
run 600 'SUMMARY [0-9]+/[0-9]+ passed' SYNC=0
sleep 15; stop selftest
sed -i '/localchainsaws.selftest/d' kit/jvm-flags.txt
grep -E 'localchainsaws-selftest\] (FAIL|SUMMARY)|server config|Loading.*localchainsaws|Crash report' selftest.log | head -40 || true
fi

if [[ " $STEPS " == *" reload "* ]]; then
log "boot 3: live config edit"
run 300 'Done \(' SYNC=0
CFG=kit/world/serverconfig/localchainsaws-server.toml
# Write in place (same file, like an editor saving over it); see the README's Tuning section.
sed 's/^\(\s*maxTargets = \)3$/\14/' "$CFG" > "$W/cfg.tmp"; cat "$W/cfg.tmp" > "$CFG"
sleep 3; say mfsrc   # ModernFix turns off Forge's config file watcher; /mfsrc reloads changed configs
timeout 60 bash -c "until docker logs $NAME 2>&1 | grep -q 'server config changed'; do sleep 2; done" || true
sed 's/^\(\s*maxTargets = \)4$/\13/' "$CFG" > "$W/cfg.tmp"; cat "$W/cfg.tmp" > "$CFG"; rm -f "$W/cfg.tmp"
sleep 5; stop reload
grep -E 'server config (loaded|changed)' reload.log | head || true
fi
log "logs in $W (boot1.log, selftest.log, reload.log); delete $W when done"
