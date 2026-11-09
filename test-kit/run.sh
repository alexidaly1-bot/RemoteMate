#!/usr/bin/env bash
# Runs the protocol tests against the fake TVs. Needs: Java 17+, Python 3 with protobuf + cryptography, pip (for androidtvremote2's message files).
set -u
cd "$(dirname "$0")"
W=work; rm -rf $W; mkdir -p $W/classes $W/atv
SRC=../app/src/main/java/com/remotemate
# 1. compile the pure-Java parts of the app (no Android needed)
javac -d $W/classes $SRC/{Proto,Certs,Button,TvDriver,GoogleTvDriver,RokuDriver,Ssdp,BrandDetector}.java TestHook.java || exit 1
javac -cp $W/classes -d $W/classes GoogleTvTest.java RokuTest.java DetectTest.java || exit 1
# 2. the fake Google TV reads the official message definitions from androidtvremote2 (as data only)
pip download --no-deps androidtvremote2==0.3.3 -d $W -q && (cd $W/atv && unzip -q -o ../androidtvremote2-*.whl)
(cd $W && openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.crt -subj /CN=faketv -days 30 2>/dev/null)
fail=0
echo "=== Photo reader ==="
java -cp $W/classes DetectTest || fail=1
echo; echo "=== Google TV (fake TV) ==="
(cd $W && python3 -I -u ../faketv.py atv/androidtvremote2 > faketv.log 2>&1 &) ; sleep 2
(cd $W && timeout 60 java -cp classes GoogleTvTest > google.log 2>&1)
sleep 1; pkill -f faketv.py
grep -v JAVA_TOOL $W/google.log; echo "--- what the fake TV received ---"; grep -E "secret|REMOTE|code shown" $W/faketv.log
echo; echo "=== Roku (fake Roku) ==="
(python3 -I -u fakeroku.py > $W/roku.log 2>&1 &) ; sleep 1.5
timeout 60 java -cp $W/classes RokuTest 2>&1 | grep -v JAVA_TOOL; sleep 0.5; pkill -f fakeroku.py; sleep 0.5
echo "--- what the fake Roku received ---"; cat $W/roku.log
echo; echo "=== Roku with mobile control turned off ==="
(python3 -I -u fakeroku.py --blocked > $W/roku-blocked.log 2>&1 &) ; sleep 1.5
timeout 30 java -cp $W/classes RokuTest blocked 2>&1 | grep -v JAVA_TOOL; pkill -f fakeroku.py
exit $fail
