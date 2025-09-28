#!/usr/bin/env bash
set -euo pipefail


# ensure DB dir exists
mkdir -p ./data


DB=./data/ShopManager.db
JAR=dist/ShopBackend.jar


if [[ ! -f "$JAR" ]]; then
echo "Jar not found in dist/. Run ./gradlew build first or copy the jar to dist/"
exit 1
fi


java -jar "$JAR" --db.path="$DB"