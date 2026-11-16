#!/usr/bin/env python3
"""Build the bridge with the pinned official WebRTC GN dependency graph."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd=None, env=None):
    print('+', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'], required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    if sys.platform != 'linux':
        parser.error('官方支持的 Android 构建环境为 Linux；使用 GitHub Actions。')
    lock = json.loads((ROOT / 'upstream.lock.json').read_text())
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    depot = work / 'depot_tools'
    if not depot.exists():
        run('git', 'clone', '--filter=blob:none', lock['depot_tools_url'], depot)
    run('git', 'checkout', '--detach', lock['depot_tools_revision'], cwd=depot)
    env = dict(os.environ, PATH=str(depot) + os.pathsep + os.environ['PATH'],
               DEPOT_TOOLS_UPDATE='0', DEPOT_TOOLS_METRICS='0')
    checkout = work / 'webrtc'
    checkout.mkdir(exist_ok=True)
    (checkout / '.gclient').write_text('solutions = ' + repr([{
        'name': 'src', 'url': lock['webrtc_url'], 'deps_file': 'DEPS',
        'managed': False, 'custom_deps': {}, 'custom_vars': {},
    }]) + '\ntarget_os = ["android"]\n')
    run('gclient', 'sync', '--revision', 'src@' + lock['webrtc_revision'],
        '--no-history', '--shallow', '--nohooks', '-j', '8', cwd=checkout, env=env)
    run('gclient', 'runhooks', cwd=checkout, env=env)
    source = checkout / 'src'
    shutil.copytree(ROOT / 'native', source / 'apm_android', dirs_exist_ok=True)
    cpu = {'arm64-v8a': 'arm64', 'armeabi-v7a': 'arm', 'x86_64': 'x64'}[args.abi]
    output = source / 'out' / args.abi
    gn_args = (f'target_os="android" target_cpu="{cpu}" is_debug=false '
               'is_component_build=false rtc_include_tests=false '
               'android_static_analysis="off" use_remoteexec=false use_siso=true '
               f'android_ndk_api_level={lock["android_api"]} symbol_level=1')
    if cpu == 'arm':
        gn_args += ' arm_version=7'
    run('gn', 'gen', output, '--args=' + gn_args, cwd=source, env=env)
    run(source / 'third_party/siso/cipd/siso', 'ninja', '-C', output,
        'apm_android:webrtc_apm_jni', '-j', '4', cwd=source, env=env)
    destination = args.output.resolve() / args.abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(output / 'libwebrtc_apm_jni.so', destination)
    shutil.copy2(output / 'args.gn', destination)
    shutil.copy2(ROOT / 'upstream.lock.json', destination)
    unstripped = output / 'lib.unstripped/libwebrtc_apm_jni.so'
    if unstripped.exists():
        shutil.copy2(unstripped, destination / 'libwebrtc_apm_jni.so.debug')
    run(sys.executable, source / 'tools_webrtc/libs/generate_licenses.py',
        '--buildfile-dirs', output, '--targets', '//apm_android:webrtc_apm_jni',
        destination, cwd=source, env=env)


if __name__ == '__main__':
    main()
