#!/bin/sh
# 验收闸门：构建 + 全部回归断言（黄金文件逐字节 diff）
set -e
cd "$(dirname "$0")"
sh build.sh
java -cp out bpconv.TestHarness "$@"
