#!/bin/sh
# بناء نسخة الويندوز - المبرمج محمد سعيد
# VERSION_CODE=12 sh build.sh
set -e
cd "$(dirname "$0")"
CODE=${VERSION_CODE:-0}
NODE_WIN=${NODE_WIN:-22.23.5}
npx esbuild server.js --bundle --platform=node --target=node22 \
  --loader:.html=text --loader:.sh=text --external:cpu-features '--external:*.node' \
  --define:__APP_VERSION__="\"1.$CODE\"" --define:__APP_CODE__=$CODE \
  --outfile=dist/ghost-hunter.js --log-level=warning
cat > dist/sea-config.json <<JSON
{ "main": "dist/ghost-hunter.js", "output": "dist/sea-prep.blob", "disableExperimentalSEAWarning": true, "useCodeCache": false, "useSnapshot": false }
JSON
node --experimental-sea-config dist/sea-config.json
if [ ! -f "dist/node-$NODE_WIN.exe" ]; then
  (cd dist && npm pack "node-win-x64@$NODE_WIN" --silent && tar xzf "node-win-x64-$NODE_WIN.tgz" && cp package/bin/node.exe "node-$NODE_WIN.exe" && rm -rf package "node-win-x64-$NODE_WIN.tgz")
fi
cp "dist/node-$NODE_WIN.exe" dist/MsaeedGhostHunter.exe
npx postject dist/MsaeedGhostHunter.exe NODE_SEA_BLOB dist/sea-prep.blob --sentinel-fuse NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2
ls -la dist/MsaeedGhostHunter.exe
