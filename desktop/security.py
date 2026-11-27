"""Windows DPAPI encryption for explicitly saved secrets; no plaintext API key files."""
from __future__ import annotations
import base64
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path

from core import APP_DIR, json_write

SECRET_FILE = APP_DIR / 'secure_api_keys.dat'


class _Blob(ctypes.Structure):
    _fields_ = [('cbData', wintypes.DWORD), ('pbData', ctypes.POINTER(ctypes.c_byte))]


def _dpapi(payload: bytes, encrypt: bool) -> bytes:
    if os.name != 'nt':
        raise RuntimeError('此功能仅支持 Windows DPAPI')
    data_buffer = ctypes.create_string_buffer(payload)
    source = _Blob(len(payload), ctypes.cast(data_buffer, ctypes.POINTER(ctypes.c_byte)))
    result = _Blob()
    crypt = ctypes.windll.crypt32
    if encrypt:
        ok = crypt.CryptProtectData(ctypes.byref(source), 'SubtitleToolPC', None, None, None, 1,
                                    ctypes.byref(result))
    else:
        ok = crypt.CryptUnprotectData(ctypes.byref(source), None, None, None, None, 1,
                                      ctypes.byref(result))
    if not ok:
        raise OSError('Windows DPAPI 加密/解密失败')
    try:
        return ctypes.string_at(result.pbData, result.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(result.pbData)


def save_keys(data: dict[str, str]) -> None:
    """Only user-initiated save. Encryption is bound to Windows profile."""
    encrypted = _dpapi(json.dumps(data, ensure_ascii=False).encode('utf-8'), True)
    json_write(SECRET_FILE, {'dpapi': base64.b64encode(encrypted).decode('ascii')})


def load_keys() -> dict[str, str]:
    if not SECRET_FILE.is_file():
        return {}
    outer = json.loads(SECRET_FILE.read_text(encoding='utf-8'))
    raw = base64.b64decode(outer['dpapi'], validate=True)
    obj = json.loads(_dpapi(raw, False).decode('utf-8'))
    return {k:v for k,v in obj.items() if isinstance(v, str)}


def delete_keys() -> None:
    SECRET_FILE.unlink(missing_ok=True)
