#!/bin/sh
# 零依赖构建证明：单条 javac，无任何第三方 -cp / jar（任务单验收闸门 §1）
set -e
cd "$(dirname "$0")"
rm -rf out
javac -encoding UTF-8 -d out src/bpconv/*.java test/bpconv/*.java
echo "BUILD OK (JDK javac 直编，零第三方依赖)"
