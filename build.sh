#!/usr/bin/env bash
# Copyright besu-blockprof contributors.
# SPDX-License-Identifier: Apache-2.0
# Builds besu-blockprof.jar; Java 21 bytecode so it runs on any JVM that can run Besu.
# besu-blockprof is the same jar behind a launcher script, so it runs without java -jar.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
find build -name '*.class' -delete
javac --release 21 -Xlint:all -d build src/*.java
jar --create --file besu-blockprof.jar --main-class BesuBlockProf -C build . -C res report.html
cat src/launcher.sh besu-blockprof.jar > besu-blockprof
chmod +x besu-blockprof
echo "built $(pwd)/besu-blockprof and $(pwd)/besu-blockprof.jar"
