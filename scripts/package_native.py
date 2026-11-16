#!/usr/bin/env python3
"""Verify and import native CI artifacts into the Android library."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ABIS = {'armeabi-v7a': 'ARM', 'arm64-v8a': 'AArch64', 'x86_64': 'Advanced Micro Devices X86-64'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path)
    parser.add_argument('--readelf', default='readelf')
    args = parser.parse_args()
    lock = json.loads((ROOT / 'upstream.lock.json').read_text())
    manifest = {'upstream': lock, 'libraries': {}}
    for abi, machine in ABIS.items():
        matches = list(args.artifacts.glob(f'**/{abi}/libwebrtc_apm_jni.so'))
        if len(matches) != 1:
            raise SystemExit(f'Expected exactly one library for {abi}: {matches}')
        library = matches[0]
        if json.loads((library.parent / 'upstream.lock.json').read_text()) != lock:
            raise SystemExit('Mixed upstream versions: ' + str(library))
        header = subprocess.check_output([args.readelf, '-h', str(library)], text=True)
        if machine not in header:
            raise SystemExit(f'Wrong ELF architecture for {abi}')
        symbols = subprocess.check_output([args.readelf, '--dyn-syms', '--wide', str(library)], text=True)
        exported = []
        for line in symbols.splitlines():
            parts = line.split()
            if len(parts) >= 8 and parts[4] in ('GLOBAL', 'WEAK') and parts[5] == 'DEFAULT' and parts[6] != 'UND':
                exported.append(parts[7].split('@')[0])
        if set(exported) != {'JNI_OnLoad'}:
            raise SystemExit(f'Unexpected exported ABI for {abi}: {exported}')
        dynamic = subprocess.check_output([args.readelf, '-d', str(library)], text=True)
        import re
        needed = re.findall(r'Shared library: \[(.*?)\]', dynamic)
        allowed = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libatomic.so'}
        if set(needed) - allowed:
            raise SystemExit(f'Unexpected dependencies for {abi}: {needed}')
        target = ROOT / 'apm/build/native/jni' / abi
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(library, target / library.name)
        resources = ROOT / 'apm/build/native/resources/META-INF/webrtc-apm' / abi
        resources.mkdir(parents=True, exist_ok=True)
        for name in ['LICENSE.md', 'args.gn', 'upstream.lock.json']:
            shutil.copy2(library.parent / name, resources / name)
        manifest['libraries'][abi] = {'sha256': hashlib.sha256(library.read_bytes()).hexdigest(), 'needed': needed}
    target = ROOT / 'apm/build/native/resources/META-INF/webrtc-apm/build-info.json'
    target.write_text(json.dumps(manifest, indent=2) + '\n')
    shutil.copy2(ROOT / 'LICENSE', target.parent / 'LICENSE.webrtc-apm-android')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
