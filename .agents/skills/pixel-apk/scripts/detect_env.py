import json, os, platform, re, shutil, subprocess, urllib.request
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

def command(args):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=12)
        return r.returncode == 0, (r.stdout + r.stderr).strip()
    except (OSError, subprocess.TimeoutExpired):
        return False, ""

def probe(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            r.read(1)
            return {"url": url, "reachable": True, "status": r.status}
    except Exception as e:
        return {"url": url, "reachable": False, "error": type(e).__name__}

def main():
    java_ok, version = command(["java", "-version"])
    match = re.search(r'version "(\d+)', version)
    java_ok = java_ok and bool(match) and int(match[1]) >= 17
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")
    gh_ok, _ = command(["gh", "auth", "status"])
    urls = [
        "https://dl.google.com/android/maven2/com/android/tools/build/gradle/9.5.1/gradle-9.5.1.pom",
        "https://services.gradle.org/distributions/gradle-9.9.1-bin.zip.sha256",
        "https://maven-central.storage-download.googleapis.com/maven2/junit/junit/4.13.2/junit-4.13.2.pom"]
    with ThreadPoolExecutor(max_workers=3) as pool:
        network = list(pool.map(probe, urls))
    sdk_ready = bool(sdk) and (Path(sdk) / "platforms").is_dir()
    local = java_ok and sdk_ready and all(p["reachable"] for p in network)
    mode = "b" if gh_ok else ("a" if platform.system() == "Windows" else "c") if local else "none"
    print(json.dumps({"mode": mode, "os": platform.system(), "java17plus": java_ok,
        "sdk": sdk or None, "sdk_platforms_present": sdk_ready, "gh_authenticated": gh_ok,
        "sdkmanager": shutil.which("sdkmanager") or shutil.which("sdkmanager.bat"),
        "gradle": shutil.which("gradle"), "kvm": os.access("/dev/kvm", os.R_OK | os.W_OK),
        "network": network, "build_verified": False}, indent=2))
if __name__ == "__main__":
    main()

