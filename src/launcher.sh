#!/bin/sh
# Copyright besu-blockprof contributors.
# SPDX-License-Identifier: Apache-2.0
# The jar is appended to this script. The Java that runs Besu is new enough and has jdk.jfr.
java=java
for pid in $(pgrep -f org.hyperledger.besu.Besu 2>/dev/null); do
  exe=$(readlink "/proc/$pid/exe" 2>/dev/null) || continue
  case $exe in */java) [ -x "$exe" ] && java=$exe && break ;; esac
done
exec "$java" -jar "$0" "$@"
