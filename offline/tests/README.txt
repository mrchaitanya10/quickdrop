Run from the extracted archive root with JDK 17+ and Python 3.9+ with Tk.
Install qrcode and zeroconf in your Python environment, or use the Linux
package dependencies. No physical camera is needed for software protocol tests.

Download ZXing core 3.5.3 to tests/zxing.jar from its Maven Central artifact:
https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
This is the decoder dependency already declared in the Android Gradle build.

mkdir -p tests/classes
javac -cp tests/zxing.jar -d tests/classes QuickDropAndroid/app/src/main/java/in/theclabs/quickdrop/LocalTransferServer.java QuickDropAndroid/app/src/main/java/in/theclabs/quickdrop/QrHandshake.java tests/*.java
java -Dsun.net.http.allowRestrictedHeaders=true -cp tests/classes ServerCheck
python3 tests/quickdrop-linux-check.py
python3 tests/quickdrop-remember-check.py
python3 tests/quickdrop-friendly-check.py
python3 tests/quickdrop-qr-check.py

The QR test advertises/discovers a synthetic phone on loopback multicast,
checks the proof exchange and transferred bytes, and decodes a generated QR
with the Java scanner pipeline. It does not validate physical Android NSD,
camera focus/permissions or Chromebook/hotspot multicast forwarding.
