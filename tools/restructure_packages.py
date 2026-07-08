# -*- coding: utf-8 -*-
"""将 MyLunarCore 主模块 Java 包结构扁平化为少量顶层包。"""
import os
import re
import shutil

BASE = "cn.itcast.demo.mylunarcore"
ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "src", "main", "java", *BASE.split("."))
TEST_ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "src", "test", "java", *BASE.split("."))

# 旧包后缀 -> 新包名（相对 mylunarcore，越长越靠前匹配）
PKG_MAP = [
    ("domain.player", "model"),
    ("domain.game", "model"),
    ("battle.factory", "battle"),
    ("battle.repo", "repo"),
    ("battle.runtime", "battle"),
    ("battle.service", "battle"),
    ("challenge.domain", "model"),
    ("challenge.repo", "repo"),
    ("challenge.runtime", "challenge"),
    ("challenge.service", "challenge"),
    ("gacha.config", "gacha"),
    ("gacha.domain", "model"),
    ("gacha.repo", "repo"),
    ("gacha.service", "gacha"),
    ("rogue.domain", "model"),
    ("rogue.repo", "repo"),
    ("rogue.runtime", "rogue"),
    ("rogue.service", "rogue"),
    ("scene.repo", "repo"),
    ("scene.runtime", "scene"),
    ("scene.service", "scene"),
    ("item.repo", "repo"),
    ("item.runtime", "item"),
    ("item.service", "item"),
    ("repository.player", "repo"),
    ("repository.game", "repo"),
    ("player.service", "player"),
    ("netty.cmd", "net"),
    ("netty.codec", "net"),
    ("netty.handler", "net"),
    ("netty.idempotency", "net"),
    ("netty.kcp", "net"),
    ("netty.netpack", "net"),
    ("netty.server", "net"),
    ("netty.ssl", "net"),
    ("event.battle", "common"),
    ("event.listener", "common"),
    ("admin.config", "admin"),
    ("admin.rbac", "admin"),
    ("admin.web", "admin"),
    ("domain", "model"),
    ("battle", "battle"),
    ("challenge", "challenge"),
    ("gacha", "gacha"),
    ("rogue", "rogue"),
    ("scene", "scene"),
    ("item", "item"),
    ("repository", "repo"),
    ("player", "player"),
    ("netty", "net"),
    ("logging", "common"),
    ("metrics", "common"),
    ("cache", "common"),
    ("event", "common"),
    ("hotfix", "common"),
    ("reload", "common"),
    ("resource", "common"),
    ("activity", "common"),
    ("game", "common"),
    ("sync", "player"),
    ("session", "player"),
    ("admin", "admin"),
    ("config", "config"),
]


def map_package(old_rel: str) -> str:
    """old_rel 如 battle.repo -> repo（最长精确匹配）"""
    for old, new in sorted(PKG_MAP, key=lambda x: -len(x[0])):
        if old_rel == old:
            return new
    return old_rel


def collect_java_files(root):
    for dp, _, fs in os.walk(root):
        for f in fs:
            if f.endswith(".java"):
                yield os.path.join(dp, f)


def main():
    moves = []  # (src, dst, old_pkg, new_pkg)
    for path in collect_java_files(ROOT):
        if path.endswith("package-info.java"):
            os.remove(path)
            continue
        rel_dir = os.path.relpath(os.path.dirname(path), ROOT).replace("\\", ".")
        if rel_dir == ".":
            continue
        fname = os.path.basename(path)
        new_pkg = map_package(rel_dir)
        new_dir = os.path.join(ROOT, *new_pkg.split("."))
        dst = os.path.join(new_dir, fname)
        old_pkg = rel_dir
        moves.append((path, dst, old_pkg, new_pkg))

    # 先移动文件
    for src, dst, old_pkg, new_pkg in moves:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.abspath(src) != os.path.abspath(dst):
            if os.path.exists(dst):
                raise SystemExit(f"冲突: {dst}")
            shutil.move(src, dst)

    # 构建 import 替换表（完整包名）
    replacements = []
    for old_suffix, new_suffix in PKG_MAP:
        replacements.append((f"{BASE}.{old_suffix}", f"{BASE}.{new_suffix}"))
    replacements.sort(key=lambda x: -len(x[0]))

    def rewrite_file(path):
        with open(path, encoding="utf-8", errors="ignore") as f:
            text = f.read()
        orig = text
        for src, dst in replacements:
            text = text.replace(src, dst)
        # 更新本文件 package 声明
        if path.startswith(ROOT):
            rel = os.path.relpath(os.path.dirname(path), ROOT).replace("\\", ".")
            if rel != ".":
                new_pkg = map_package(rel)
                text = re.sub(
                    r"^package\s+cn\.itcast\.demo\.mylunarcore\.[\w.]+;",
                    f"package {BASE}.{new_pkg};",
                    text,
                    count=1,
                    flags=re.MULTILINE,
                )
        if text != orig:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)

    for path in collect_java_files(ROOT):
        rewrite_file(path)
    if os.path.isdir(TEST_ROOT):
        for path in collect_java_files(TEST_ROOT):
            if path.endswith("package-info.java"):
                os.remove(path)
                continue
            rewrite_file(path)

    # 删除空目录
    for dp, dirs, files in os.walk(ROOT, topdown=False):
        if not dirs and not files:
            try:
                os.rmdir(dp)
            except OSError:
                pass

    print(f"Moved {len(moves)} files")
    pkgs = sorted({m[3] for m in moves})
    print("Top-level packages:", ", ".join(pkgs))


if __name__ == "__main__":
    main()
