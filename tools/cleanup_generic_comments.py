# -*- coding: utf-8 -*-
"""移除 augment_java_comments_v2.py 生成的无信息量行尾注释。"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP = ("target", "generated-sources", "tools")

# 仅匹配整段注释为这些占位文案的行尾注释
GENERIC_ONLY = re.compile(
    r"\s// (?:"
    r"条件判断|构建 Protobuf/对象|设置 Protobuf/对象字段|赋值/调用|执行语句|"
    r"创建新对象|返回|循环遍历|读取 Map/对象属性|添加到集合|从 Map 移除|"
    r"尝试执行|捕获异常|批量写入 Protobuf 列表|字段赋值|写入 Map|"
    r"填充实体字段|读取数据库列|数据库操作|SQL 语句|抛出异常|"
    r"Mock 桩行为|Mock 依赖字段|测试断言|成员字段|日志/logger 名常量|"
    r"构造协议响应|空值校验|不可变依赖|本类日志记录器|"
    r"Channel 上存放玩家 uid 的属性键|无数据|返回空集合"
    r")\s*$"
)

# 「有意义注释 + 占位文案」时只去掉占位部分
GENERIC_SUFFIX = re.compile(
    r"\s// ([^/]+?) \+ (?:条件判断|构建 Protobuf/对象|设置 Protobuf/对象字段)\s*$"
)


def clean_line(line: str) -> str:
    s = line.rstrip("\n")
    m = GENERIC_SUFFIX.search(s)
    if m:
        return m.group(1).rstrip() + "\n"
    if GENERIC_ONLY.search(s):
        return re.sub(r"\s// .*$", "", s) + "\n"
    return line


AUTO_JAVADOC = re.compile(
    r"^\s*\*\s*(?:业务方法|获取|处理协议请求|加载数据|按条件查询|"
    r"列出记录|统计数量|更新数据库|新增记录|设置字段|转换为|"
    r"应用变更|确保记录存在|映射 ResultSet 行|事件/回调处理)"
    r"（方法名 \w+）。\s*$"
)


def strip_auto_javadoc(lines: list[str]) -> tuple[list[str], bool]:
    out = []
    changed = False
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("/**"):
            block = [lines[i]]
            j = i + 1
            while j < len(lines) and not lines[j].strip().startswith("*/"):
                block.append(lines[j])
                j += 1
            if j < len(lines):
                block.append(lines[j])
            body = block[1:-1]
            if body and all(AUTO_JAVADOC.match(b) for b in body):
                changed = True
                i = j + 1
                continue
            out.extend(block)
            i = j + 1
            continue
        out.append(lines[i])
        i += 1
    return out, changed


def process_file(path: str) -> bool:
    with open(path, encoding="utf-8", errors="ignore") as f:
        original = f.readlines()
    lines = [clean_line(l) for l in original]
    lines, javadoc_changed = strip_auto_javadoc(lines)
    if lines != original or javadoc_changed:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.writelines(lines)
        return True
    return False


def main():
    updated = []
    for dp, _, fs in os.walk(ROOT):
        if any(s in dp for s in SKIP):
            continue
        for fn in fs:
            if fn.endswith(".java"):
                p = os.path.join(dp, fn)
                if process_file(p):
                    updated.append(os.path.relpath(p, ROOT))
    print(f"Cleaned {len(updated)} files")
    for p in sorted(updated):
        print(f"  {p}")


if __name__ == "__main__":
    main()
