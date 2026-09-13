# -*- coding: utf-8 -*-
"""
在当前网络环境下 `git push` 的长连接会被掐断，此脚本改用 GitHub Git Data REST API
推送工作树（增量：只上传相对远端有变化的文件）。

用法：
    python tools/gh_push.py [提交信息]

Token 读取顺序：环境变量 GITHUB_TOKEN → 仓库根目录 .gh-token 文件（已在 .gitignore 中）。
"""
import base64
import json
import os
import subprocess
import sys
import tempfile

API = "https://api.github.com"
OWNER = "jinzhongxuan2-glitch"
REPO = "wakeup-schedule"
BRANCH = "main"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_token():
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        return token.strip()
    token_file = os.path.join(ROOT, ".gh-token")
    if os.path.exists(token_file):
        with open(token_file, encoding="utf-8") as f:
            return f.read().strip()
    sys.exit("缺少 Token：请设置环境变量 GITHUB_TOKEN，或在仓库根目录创建 .gh-token 文件")


def gh(method, path, payload=None, token=""):
    """用 curl 发请求（本机 python urllib 的 DNS 解析到不可用 IP，curl 正常）"""
    cmd = [
        "curl", "-m", "60", "-s", "-X", method, API + path,
        "-H", "Authorization: Bearer " + token,
        "-H", "Accept: application/vnd.github+json",
        "-H", "Content-Type: application/json",
    ]
    tmp = None
    if payload is not None:
        # 大 payload 写临时文件，避开 Windows 约 32K 的命令行长度上限
        fd, tmp = tempfile.mkstemp(suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(json.dumps(payload))
        cmd += ["-d", "@" + tmp]
    try:
        for attempt in range(3):
            out = subprocess.run(cmd, capture_output=True, timeout=120)
            try:
                return json.loads(out.stdout.decode())
            except Exception:
                if attempt == 2:
                    raise RuntimeError("API 失败 %s %s: %s" % (method, path, out.stdout[:300]))
    finally:
        if tmp and os.path.exists(tmp):
            os.remove(tmp)


def local_blob_sha(rel):
    """git hash-object 算出的 SHA1 与 GitHub blob sha 一致，可用于增量对比"""
    return subprocess.check_output(["git", "hash-object", rel], cwd=ROOT).decode().strip()


def main():
    token = read_token()
    message = sys.argv[1] if len(sys.argv) > 1 else "chore: 同步代码"

    ref = gh("GET", "/repos/%s/%s/git/ref/heads/%s" % (OWNER, REPO, BRANCH), token=token)
    parent = ref["object"]["sha"]
    remote_tree_sha = gh("GET", "/repos/%s/%s/git/commits/%s" % (OWNER, REPO, parent), token=token)["tree"]["sha"]
    remote_tree = gh("GET", "/repos/%s/%s/git/trees/%s?recursive=1" % (OWNER, REPO, remote_tree_sha), token=token)
    remote = {e["path"]: e["sha"] for e in remote_tree.get("tree", []) if e["type"] == "blob"}
    print("远端父提交 %s，已有 %d 个文件" % (parent[:8], len(remote)))

    files = subprocess.check_output(["git", "ls-files"], cwd=ROOT).decode().splitlines()
    changed = []
    for rel in files:
        sha = local_blob_sha(rel)
        if remote.get(rel) != sha:
            with open(os.path.join(ROOT, rel.replace("/", os.sep)), "rb") as f:
                content = f.read()
            blob = gh("POST", "/repos/%s/%s/git/blobs" % (OWNER, REPO),
                      {"content": base64.b64encode(content).decode(), "encoding": "base64"}, token=token)
            mode = "100755" if rel == "gradlew" else "100644"
            changed.append({"path": rel, "mode": mode, "type": "blob", "sha": blob["sha"]})
            print("  更新 %s" % rel)

    if not changed:
        print("没有变化，无需推送")
        return

    tree = gh("POST", "/repos/%s/%s/git/trees" % (OWNER, REPO),
              {"base_tree": remote_tree_sha, "tree": changed}, token=token)
    commit = gh("POST", "/repos/%s/%s/git/commits" % (OWNER, REPO),
                {"message": message, "tree": tree["sha"], "parents": [parent]}, token=token)
    gh("PATCH", "/repos/%s/%s/git/refs/heads/%s" % (OWNER, REPO, BRANCH),
       {"sha": commit["sha"], "force": False}, token=token)
    print("已推送 %d 个文件 → %s" % (len(changed), commit["sha"][:8]))

    # 同步本地引用，避免与远端分叉
    subprocess.call(["git", "fetch", "origin", BRANCH], cwd=ROOT,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.call(["git", "update-ref", "refs/remotes/origin/%s" % BRANCH, commit["sha"]], cwd=ROOT)


if __name__ == "__main__":
    main()
