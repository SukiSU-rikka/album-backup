# -*- coding: utf-8 -*-
"""相册备份 v1.1.0 手工构建：kotlinc → d8 → aapt2 → zipalign → apksigner
源码为用户提供的 MediaBackup（Kotlin），包名 cn.rikka.album、沿用原图标与签名。
旧版 Java 构建脚本备份在 build_album_v10_java.py.bak。"""
import os, subprocess, sys, zipfile, hashlib

ROOT = os.path.expanduser("~/workspace/album-backup")
SDK = os.path.expanduser("~/workspace/hikari_build/android-sdk")
BT = os.path.join(SDK, "build-tools", "36.1.0")
AJAR = os.path.join(SDK, "platforms", "android-36", "android.jar")
KOTLINC = os.path.expanduser("~/workspace/tools/kotlinc/bin/kotlinc")
KS = os.path.expanduser("~/workspace/keystore/rikka.p12")
KS_PASS = "rikka2026"
B = os.path.join(ROOT, "build")
SRC = os.path.join(ROOT, "app", "src", "main")

def run(cmd, **kw):
    print("+", " ".join(cmd[:3]), "...")
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-3000:]); print(r.stderr[-3000:])
        sys.exit("构建失败: " + cmd[0])
    return r

os.makedirs(B, exist_ok=True)
kts = [os.path.join(SRC, "java", "cn", "rikka", "album", f)
       for f in ["Engine.kt", "Index.kt", "MainActivity.kt", "Saf.kt", "Bus.kt", "WorkService.kt"]]

# 1. Kotlin 编译（带 stdlib 运行时）
run([KOTLINC, "-classpath", AJAR, "-include-runtime", "-jvm-target", "17",
     "-d", os.path.join(B, "classes.jar")] + kts)

# 2. d8 转 dex
dexdir = os.path.join(B, "dex")
os.makedirs(dexdir, exist_ok=True)
run([os.path.join(BT, "d8"), "--release", "--min-api", "29", "--lib", AJAR,
     "--output", dexdir, os.path.join(B, "classes.jar")])

# 3. aapt2 编译资源 + 链接
run([os.path.join(BT, "aapt2"), "compile", "--dir", os.path.join(SRC, "res"),
     "-o", os.path.join(B, "res.zip")])
unsigned = os.path.join(B, "unsigned.apk")
run([os.path.join(BT, "aapt2"), "link", "-I", AJAR,
     "--manifest", os.path.join(SRC, "AndroidManifest.xml"),
     "--min-sdk-version", "29", "--target-sdk-version", "34",
     "--version-code", "4", "--version-name", "1.1.0",
     os.path.join(B, "res.zip"), "-o", unsigned])

# 4. 塞入 dex、对齐、签名
with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(dexdir, "classes.dex"), "classes.dex")
aligned = os.path.join(B, "aligned.apk")
run([os.path.join(BT, "zipalign"), "-f", "-p", "4", unsigned, aligned])
final = os.path.join(B, "album-backup-v1.1.0.apk")
run([os.path.join(BT, "apksigner"), "sign", "--ks", KS, "--ks-pass", "pass:" + KS_PASS,
     "--ks-key-alias", "rikka", "--key-pass", "pass:" + KS_PASS,
     "--out", final, aligned])

# 5. 校验：badging + 图标 + dex + 签名证书与旧版一致
r = run([os.path.join(BT, "aapt"), "dump", "badging", final])
assert "cn.rikka.album" in r.stdout and "versionName='1.1.0'" in r.stdout and "versionCode='4'" in r.stdout, "badging 不对"
names = zipfile.ZipFile(final).namelist()
assert "classes.dex" in names, "dex 缺失"
assert any("ic_icon" in n for n in names), "图标缺失"
def cert(apk):
    rr = subprocess.run([os.path.join(BT, "apksigner"), "verify", "--print-certs", apk],
                        capture_output=True, text=True)
    for line in rr.stdout.splitlines():
        if "SHA-256" in line:
            return line.split(": ", 1)[1].strip()
    return None
new_cert, old_cert = cert(final), cert(os.path.join(B, "signed.apk"))
print("新版证书:", new_cert)
print("旧版证书:", old_cert)
assert new_cert and new_cert == old_cert, "证书与旧版不一致，不能覆盖安装"
size = os.path.getsize(final)
print("完成:", final, size, "字节, sha256:", hashlib.sha256(open(final, "rb").read()).hexdigest()[:16])
