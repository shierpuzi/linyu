#!/usr/bin/env python3
"""构建产物安全审计：确认 loginCode **只**出现在 dev 包里，原版包干净。

## 为什么需要它

`buildConfigField` 写在 `defaultConfig` 里时，**所有 flavor 都会带上**——
2026-10-24 实测踩过一次：`app-store-debug.apk` 的 dex 里真的能搜到 loginCode。
这种错误编译器不会报，测试也不会报，只能靠扫产物发现。

## 用法

    python verify_apk.py                    # 扫 app/build/outputs/apk 下所有 APK
    python verify_apk.py 某个.apk            # 扫指定包

退出码：0 = 通过；1 = 有凭证泄漏或发现异常。
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))      # linyu/tools
REPO = os.path.dirname(HERE)                            # linyu/  （仓库根）
APK_ROOT = os.path.join(REPO, "app", "build", "outputs", "apk")
LOCAL_PROPS = os.path.join(REPO, "local.properties")

# 除了 loginCode，再扫几个「不该出现在公开包里」的特征
SENSITIVE_KEYS = {
    "QZXY_PREFILL_LOGIN_CODE": "预置 loginCode",
    "QZXY_PREFILL_TELEPHONE": "预置手机号",
    "QZXY_PREFILL_USER_ID": "预置 userId",
}


def load_secrets():
    """从 local.properties 读要扫的值。没配就跳过（公开构建本来就没有）。"""
    secrets = {}
    if not os.path.isfile(LOCAL_PROPS):
        return secrets
    for line in open(LOCAL_PROPS, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        if k in SENSITIVE_KEYS and v and len(v) >= 6:
            secrets[k] = v
    return secrets


def find_apks():
    out = []
    for dirpath, _, files in os.walk(APK_ROOT):
        for f in files:
            if f.endswith(".apk"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def scan_apk(apk, secrets):
    """返回 (命中的 key 集合, 包名, 版本名)。dex 里的字符串是原始字节，直接找。"""
    hits = set()
    pkg = ver = "?"
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not name.endswith(".dex"):
                continue
            data = z.read(name)
            for key, val in secrets.items():
                if val.encode() in data:
                    hits.add(key)
    # 包名/版本：优先 aapt2，没有就留 "?"
    # ⚠️ aapt2 的输出是 **GBK**（application-label 是中文），必须显式指定编码，
    #    否则 subprocess 用系统默认解码会在读线程里抛 UnicodeDecodeError，
    #    包名变成 "?"，dev 包就会被误判成「泄漏到非 dev 包」。
    aapt = r"C:\Android\build-tools\36.0.0\aapt2.exe"
    if os.path.isfile(aapt):
        try:
            r = subprocess.run([aapt, "dump", "badging", apk],
                               capture_output=True, timeout=60)
            text = r.stdout.decode("utf-8", "replace")
            if "package: name=" not in text:
                text = r.stdout.decode("gbk", "replace")
            m = re.search(r"package: name='([^']+)' versionCode='\d+' versionName='([^']*)'",
                          text)
            if m:
                pkg, ver = m.group(1), m.group(2)
        except Exception:
            pass
    return hits, pkg, ver


def main():
    targets = sys.argv[1:] or find_apks()
    if not targets:
        sys.exit(f"没找到 APK，先构建：{APK_ROOT}")

    secrets = load_secrets()
    if not secrets:
        print("ℹ️ local.properties 里没配预置凭证，无需审计（公开构建）")
        return 0

    print(f"审计 {len(targets)} 个 APK，监控 {len(secrets)} 项敏感值\n")
    leaks = []
    for apk in targets:
        hits, pkg, ver = scan_apk(apk, secrets)
        name = os.path.basename(apk)
        is_dev = pkg.endswith(".dev")
        if is_dev:
            status = "✅ 含凭证（预期：dev 包）" if hits else "⚠️ dev 包但没含凭证"
            print(f"  {name:28} {pkg:26} {ver:12} {status}")
        else:
            if hits:
                status = f"❌ **泄漏** {sorted(hits)}"
                leaks.append((name, pkg, sorted(hits)))
            else:
                status = "✅ 干净（无凭证）"
            print(f"  {name:28} {pkg:26} {ver:12} {status}")

    print()
    if leaks:
        print("❌ 有凭证泄漏到非 dev 包：")
        for name, pkg, keys in leaks:
            print(f"   {name} ({pkg}) → {keys}")
        print("\n  检查 app/build.gradle.kts：buildConfigField 必须写在 dev flavor 里，"
              "不能写在 defaultConfig。")
        return 1
    print("✅ 通过：凭证只在 dev 包中，原版包干净")
    return 0


if __name__ == "__main__":
    sys.exit(main())
