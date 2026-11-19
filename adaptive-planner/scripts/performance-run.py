"""Windows product benchmark launcher and 1Hz Private/WS/Available sampler. No retry."""
from pathlib import Path
import ctypes as C
from ctypes import wintypes as W
import csv
import datetime
import json
import os
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PRODUCT = ROOT / 'adaptive-planner'
run = PRODUCT / 'results/v0.3-performance' / ('run-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6])
run.mkdir(parents=True)
(PRODUCT / 'results/v0.3-performance/latest-run.txt').write_text(str(run), encoding='utf-8')
classpath = str(PRODUCT / 'planner-app/target/classes') + ';' + (PRODUCT / 'planner-app/target/classpath.txt').read_text().strip()
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe') if 'JAVA_HOME' in os.environ else 'java.exe'
# Oracle's javapath/java.exe is a launcher shim with a separate JVM child.
# Resolve java.home so process memory refers to the actual VM, not the shim.
settings = subprocess.run([java, '-XshowSettings:properties', '-version'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout.decode('utf-8', errors='replace')
home = re.search(r'^\s*java.home\s*=\s*(.+)$', settings, re.MULTILINE)
if home:
    java = str(Path(home.group(1).strip()) / 'bin/java.exe')
command = [java, '-Xms1g', '-Xmx4g', '--add-modules=jdk.incubator.vector', '-Djvector.vectorization_provider=panama', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', classpath, 'com.filteredann.planner.demo.PerformanceBenchmark', str(ROOT), str(run)]
(run / 'command.json').write_text(json.dumps(command, indent=2), encoding='utf-8')

class Memory(C.Structure):
    _fields_ = [('cb', W.DWORD), ('faults', W.DWORD)] + [(name, C.c_size_t) for name in ['peak_ws', 'working_set', 'peak_paged', 'paged', 'peak_nonpaged', 'nonpaged', 'pagefile', 'peak_pagefile', 'private']]

class SystemMemory(C.Structure):
    _fields_ = [('length', W.DWORD), ('load', W.DWORD)] + [(name, C.c_ulonglong) for name in ['total', 'available', 'total_page', 'available_page', 'total_virtual', 'available_virtual', 'extended']]

k = C.WinDLL('kernel32', use_last_error=True)
ps = C.WinDLL('psapi', use_last_error=True)
k.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
k.OpenProcess.restype = W.HANDLE
ps.GetProcessMemoryInfo.argtypes = [W.HANDLE, C.POINTER(Memory), W.DWORD]
k.GlobalMemoryStatusEx.argtypes = [C.POINTER(SystemMemory)]
k.CloseHandle.argtypes = [W.HANDLE]

print('PRODUCT_PERFORMANCE_RUN', run, flush=True)
with (run / 'console.log').open('wb') as log, (run / 'process-memory.csv').open('w', newline='', encoding='utf-8') as memory:
    child = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    (run / 'pid.json').write_text(json.dumps(dict(java_pid=child.pid, launcher_pid=os.getpid())), encoding='utf-8')
    handle = k.OpenProcess(0x410, False, child.pid)
    if not handle:
        child.terminate()
        raise OSError(C.get_last_error(), 'OpenProcess')
    writer = csv.writer(memory)
    writer.writerow(['utc', 'elapsed_seconds', 'private_bytes', 'working_set_bytes', 'system_available_bytes'])
    start = time.monotonic()
    try:
        while child.poll() is None:
            info = Memory(); info.cb = C.sizeof(info)
            system = SystemMemory(); system.length = C.sizeof(system)
            if ps.GetProcessMemoryInfo(handle, C.byref(info), C.sizeof(info)) and k.GlobalMemoryStatusEx(C.byref(system)):
                writer.writerow([datetime.datetime.now(datetime.timezone.utc).isoformat(), time.monotonic()-start, info.private, info.working_set, system.available])
                memory.flush()
            if '--auto-after' in sys.argv and (run / 'baseline-complete').exists() and not (run / 'continue-after').exists():
                (run / 'continue-after').write_text('com.filteredann.planner.engine.MatchedIdExactIndex', encoding='utf-8')
            time.sleep(1)
    except BaseException:
        child.terminate()
        child.wait()
        raise
    finally:
        k.CloseHandle(handle)
    code = child.wait()
    state_file = run / 'state.json'
    state = json.loads(state_file.read_text()) if state_file.exists() else dict(state='INCOMPLETE', reason='No terminal Java record')
    passed = code == 0 and state.get('state') == 'COMPLETED'
    (run / 'launcher-result.json').write_text(json.dumps(dict(java_exit_code=code, passed=passed, elapsed_seconds=time.monotonic()-start), indent=2), encoding='utf-8')
    print('PRODUCT_PERFORMANCE_EXIT', code, state.get('state'), flush=True)
    sys.exit(0 if passed else code or 1)
