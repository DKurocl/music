import argparse, json, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
PINS = {
 "agp": ("9.5.1", "https://dl.google.com/android/maven2/com/android/tools/build/gradle/9.5.1/gradle-9.5.1.pom"),
 "gradle": ("9.9.1", "https://services.gradle.org/distributions/gradle-9.9.1-bin.zip.sha256"),
 "bom": ("2026.09.00", "https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom"),
 "material3": ("1.5.0-rc01", "https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-rc01/material3-android-1.5.0-rc01.pom"),
 "ksp": ("2.3.13", "https://maven-central.storage-download.googleapis.com/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/2.3.13/symbol-processing-gradle-plugin-2.3.13.pom")
}
def check(item):
    name, (version, url) = item
    try:
        with urllib.request.urlopen(url, timeout=8) as r:
            body = r.read(1024 * 1024).decode("utf-8")
        if not body.strip() or (name != "gradle" and "<project" not in body):
            raise ValueError("Not a Maven POM")
        return {"name": name, "version": version, "resolved": True, "url": url}
    except Exception as e:
        return {"name": name, "version": version, "resolved": False, "url": url, "error": type(e).__name__}
def main():
    p = argparse.ArgumentParser()
    p.add_argument("--network", action="store_true")
    a = p.parse_args()
    if not a.network:
        print(json.dumps({k:v[0] for k,v in PINS.items()}, indent=2))
        print("Pins only; resolution and compatibility NOT verified.")
        return
    with ThreadPoolExecutor(max_workers=5) as pool:
        results = list(pool.map(check, PINS.items()))
    print(json.dumps({"artifacts":results, "compiler_compatibility":"requires AGP embedded Kotlin inspection", "build_verified":False}, indent=2))
    sys.exit(0 if all(x["resolved"] for x in results) else 2)
if __name__ == "__main__":
    main()

