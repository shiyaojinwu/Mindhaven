#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
bash ./start.sh "$@"
result=$?
printf '\n启动器已结束，按回车关闭。'
read -r _
exit "$result"
