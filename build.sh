#!/usr/bin/env bash
# Copyright besu-blockprof contributors.
# SPDX-License-Identifier: Apache-2.0
# Builds blockprof.jar; Java 21 bytecode so it runs on any JVM that can run Besu.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
find build -name '*.class' -delete
javac --release 21 -Xlint:all -d build src/*.java
jar --create --file blockprof.jar --main-class BlockProf -C build . -C res report.html
echo "built $(pwd)/blockprof.jar"
