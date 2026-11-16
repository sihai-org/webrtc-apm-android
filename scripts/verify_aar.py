#!/usr/bin/env python3
"""Check the delivered AAR, including embedded native provenance and licenses."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('aar', type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.aar) as aar:
        expected = {f'jni/{abi}/libwebrtc_apm_jni.so' for abi in ('armeabi-v7a', 'arm64-v8a', 'x86_64')}
        if {name for name in aar.namelist() if name.endswith('.so')} != expected:
            raise SystemExit('Unexpected/missing AAR native payload')
        if b'org.sihai.apm.NativeBridge' not in aar.read('proguard.txt'):
            raise SystemExit('JNI consumer keep rules missing')
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
            classes.getinfo('org/sihai/apm/NativeBridge.class')
            info = json.loads(classes.read('META-INF/webrtc-apm/build-info.json'))
            revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
            if info['wrapper_revision'] != revision:
                raise SystemExit('AAR wrapper revision mismatch')
            if info['upstream'] != json.loads((ROOT / 'upstream.lock.json').read_text()):
                raise SystemExit('AAR upstream revision mismatch')
            for abi, library in info['libraries'].items():
                digest = hashlib.sha256(aar.read(f'jni/{abi}/libwebrtc_apm_jni.so')).hexdigest()
                if digest != library['sha256']:
                    raise SystemExit(f'Native payload changed during packaging: {abi}')
                if not classes.read(f'META-INF/webrtc-apm/{abi}/LICENSE.md'):
                    raise SystemExit(f'Empty dependency license: {abi}')
            classes.getinfo('META-INF/webrtc-apm/LICENSE.webrtc-apm-android')
    print(f'Verified {args.aar}: 3 ABIs, JNI keep rules, exact native hashes, source provenance, licenses')


if __name__ == '__main__':
    main()
