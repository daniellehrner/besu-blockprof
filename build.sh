#!/usr/bin/env bash
# Copyright besu-blockprof contributors.
# SPDX-License-Identifier: Apache-2.0
# Builds besu-blockprof.jar; Java 21 bytecode so it runs on any JVM that can run Besu.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
find build -name '*.class' -delete
javac --release 21 -Xlint:all -d build src/*.java
jar --create --file besu-blockprof.jar --main-class BesuBlockProf -C build . -C res report.html
echo "built $(pwd)/besu-blockprof.jar"
