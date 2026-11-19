"""Supplemental real-JVM memory sampling when the original launcher was a shim."""
import ctypes as C
from ctypes import wintypes as W
import csv
import datetime
from pathlib import Path
import sys
import time

pid=int(sys.argv[1]);out=Path(sys.argv[2])
class Memory(C.Structure):
    _fields_=[('cb',W.DWORD),('faults',W.DWORD)]+[(n,C.c_size_t) for n in ['peak_ws','ws','peak_paged','paged','peak_nonpaged','nonpaged','pagefile','peak_pagefile','private']]
class SystemMemory(C.Structure):
    _fields_=[('length',W.DWORD),('load',W.DWORD)]+[(n,C.c_ulonglong) for n in ['total','available','total_page','available_page','total_virtual','available_virtual','extended']]
k=C.WinDLL('kernel32',use_last_error=True);ps=C.WinDLL('psapi',use_last_error=True)
k.OpenProcess.argtypes=[W.DWORD,W.BOOL,W.DWORD];k.OpenProcess.restype=W.HANDLE
ps.GetProcessMemoryInfo.argtypes=[W.HANDLE,C.POINTER(Memory),W.DWORD]
k.GlobalMemoryStatusEx.argtypes=[C.POINTER(SystemMemory)];k.CloseHandle.argtypes=[W.HANDLE]
k.GetExitCodeProcess.argtypes=[W.HANDLE,C.POINTER(W.DWORD)]
handle=k.OpenProcess(0x410,False,pid)
if not handle:raise OSError(C.get_last_error(),'OpenProcess real JVM')
with out.open('x',encoding='utf-8',newline='') as f:
    w=csv.writer(f);w.writerow(['utc','actual_java_pid','private_bytes','working_set_bytes','system_available_bytes'])
    try:
        while True:
            exit_code=W.DWORD()
            if not k.GetExitCodeProcess(handle,C.byref(exit_code)) or exit_code.value!=259:break
            m=Memory();m.cb=C.sizeof(m);s=SystemMemory();s.length=C.sizeof(s)
            if not ps.GetProcessMemoryInfo(handle,C.byref(m),C.sizeof(m)):break
            if not k.GlobalMemoryStatusEx(C.byref(s)):raise OSError(C.get_last_error(),'GlobalMemoryStatusEx')
            w.writerow([datetime.datetime.now(datetime.timezone.utc).isoformat(),pid,m.private,m.ws,s.available]);f.flush();time.sleep(1)
    finally:k.CloseHandle(handle)
