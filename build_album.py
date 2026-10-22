#!/usr/bin/env python3
"""相册全备份 构建脚本：javac -> d8 -> aapt2 -> 注入dex -> apksigner 签名 -> 验证"""
import os, subprocess, sys, zipfile, shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.expanduser("~/workspace/hikari_build/android-sdk")
AJAR = os.path.join(SDK, "platforms/android-36/android.jar")
BT = os.path.join(SDK, "build-tools/36.1.0")
BUILD = os.path.join(ROOT, "build")
OUT = os.path.expanduser("~/workspace/your_files/album-backup-v1.0.0.apk")
KS = os.path.expanduser("~/workspace/keystore/rikka.p12")
KS_PASS = "rikka2026"

def run(cmd, **kw):
    print("+", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.stdout: print(r.stdout[-3000:])
    if r.returncode != 0:
        print(r.stderr[-3000:]); sys.exit("FAILED: " + cmd[0])
    return r

def main():
    if os.path.exists(BUILD): shutil.rmtree(BUILD)
    os.makedirs(os.path.join(BUILD, "classes"))
    os.makedirs(os.path.join(BUILD, "dex"))
    srcs = []
    for dp, _, fns in os.walk(os.path.join(ROOT, "src")):
        for fn in fns:
            if fn.endswith(".java"): srcs.append(os.path.join(dp, fn))
    run(["javac", "-source", "8", "-target", "8", "-cp", AJAR,
         "-d", os.path.join(BUILD, "classes")] + srcs)
    run(["jar", "cf", os.path.join(BUILD, "classes.jar"), "-C", os.path.join(BUILD, "classes"), "."])
    run([os.path.join(BT, "d8"), "--release", "--min-api", "26",
         "--output", os.path.join(BUILD, "dex"), os.path.join(BUILD, "classes.jar")])
    run([os.path.join(BT, "aapt2"), "compile", "--dir", os.path.join(ROOT, "res"),
         "-o", os.path.join(BUILD, "res.zip")])
    run([os.path.join(BT, "aapt2"), "link", "-o", os.path.join(BUILD, "unsigned.apk"),
         "-I", AJAR, "--manifest", os.path.join(ROOT, "AndroidManifest.xml"),
         os.path.join(BUILD, "res.zip")])
    # 注入 dex；resources.arsc 保持 STORED 不压缩（Android R+ 硬要求）
    final_unsigned = os.path.join(BUILD, "final-unsigned.apk")
    zin = zipfile.ZipFile(os.path.join(BUILD, "unsigned.apk"))
    zout = zipfile.ZipFile(final_unsigned, "w")
    for item in zin.infolist():
        data = zin.read(item.filename)
        ct = zipfile.ZIP_STORED if item.filename == "resources.arsc" else item.compress_type
        zout.writestr(item.filename, data, compress_type=ct)
    zout.writestr("classes.dex", open(os.path.join(BUILD, "dex/classes.dex"), "rb").read(),
                  compress_type=zipfile.ZIP_DEFLATED)
    zout.close(); zin.close()
    signed = os.path.join(BUILD, "signed.apk")
    run([os.path.join(BT, "apksigner"), "sign", "--ks", KS, "--ks-pass", "pass:" + KS_PASS,
         "--ks-key-alias", "rikka", "--key-pass", "pass:" + KS_PASS,
         "--out", signed, final_unsigned])
    run([os.path.join(BT, "apksigner"), "verify", "--print-certs", signed])
    r = run([os.path.join(BT, "aapt2"), "dump", "badging", signed])
    assert "cn.rikka.album" in r.stdout and "versionName='1.0.0'" in r.stdout, "badging 不对"
    # 包内结构自检
    z = zipfile.ZipFile(signed)
    names = z.namelist()
    assert "classes.dex" in names and "resources.arsc" in names, names
    arsc = [i for i in z.infolist() if i.filename == "resources.arsc"][0]
    assert arsc.compress_type == zipfile.ZIP_STORED, "resources.arsc 被压缩了"
    icon = [n for n in names if "ic_icon" in n]
    assert icon, "图标缺失: " + str(names)
    print("包内文件:", names)
    shutil.copy(signed, OUT)
    print("APK ->", OUT, os.path.getsize(OUT), "bytes")
    # 源码包
    srczip = os.path.expanduser("~/workspace/your_files/album-backup-v1.0.0-src.zip")
    z = zipfile.ZipFile(srczip, "w", zipfile.ZIP_DEFLATED)
    for base in ("src", "res"):
        for dp, _, fns in os.walk(os.path.join(ROOT, base)):
            for fn in fns:
                p = os.path.join(dp, fn)
                z.write(p, os.path.relpath(p, ROOT))
    z.write(os.path.join(ROOT, "AndroidManifest.xml"), "AndroidManifest.xml")
    z.write(os.path.join(ROOT, "build_album.py"), "build_album.py")
    z.close()
    print("SRC ->", srczip, os.path.getsize(srczip), "bytes")

if __name__ == "__main__":
    main()
