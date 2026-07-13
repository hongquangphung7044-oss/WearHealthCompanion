#!/usr/bin/env python3
"""上传单个 GitHub Actions secret"""
import base64
import json
import os
import sys
import urllib.request
from nacl.public import PublicKey, SealedBox

TOKEN = os.environ['GH_TOKEN']
OWNER = os.environ['OWNER']
REPO = os.environ['REPO']
SECRET_NAME = os.environ['SECRET_NAME']
SECRET_VALUE = os.environ['SECRET_VALUE']


def main():
    # 获取 public key
    url = f"https://api.github.com/repos/{OWNER}/{REPO}/actions/secrets/public-key"
    req = urllib.request.Request(url, headers={
        "Authorization": f"token {TOKEN}",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    key_id = data["key_id"]
    public_key_b64 = data["key"]
    print(f"public key id: {key_id}")

    # 加密
    public_key = PublicKey(base64.b64decode(public_key_b64))
    sealed = SealedBox(public_key).encrypt(SECRET_VALUE.encode())
    encrypted = base64.b64encode(sealed).decode()

    # 上传
    url = f"https://api.github.com/repos/{OWNER}/{REPO}/actions/secrets/{SECRET_NAME}"
    body = json.dumps({"encrypted_value": encrypted, "key_id": key_id}).encode()
    req = urllib.request.Request(url, method="PUT", data=body, headers={
        "Authorization": f"token {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"  {SECRET_NAME}: HTTP {resp.status}")
    except urllib.error.HTTPError as e:
        print(f"  {SECRET_NAME}: HTTP {e.code} - {e.read().decode()}")


if __name__ == "__main__":
    main()
