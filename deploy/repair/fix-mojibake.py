#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
历史乱码清理工具（UTF-8 字节被按 GBK 解读形成的中文乱码）

问题形态
    "压测用户1" 写入时被按 GBK 解读成 UTF-8 字节序列，存成了 "鍘嬫祴鐢ㄦ埛1"。
    读取时按 UTF-8 正常解码，看到的仍是这串错字。

还原原理
    错字 -> 按 GBK 编码取回原始字节 -> 这些字节正好是正确中文的 UTF-8 编码。
    还原成立的充要条件是这两步都能无损完成，因此天然具备可逆校验。

用法
    python fix-mojibake.py                  # 只扫描，不写库（默认）
    python fix-mojibake.py --apply          # 备份后执行修复
    python fix-mojibake.py --apply --yes    # 跳过交互确认（自动化用）

连接参数从环境变量读取，默认对齐 application.yaml：
    MYSQL_HOST  MYSQL_PORT  MYSQL_USER  MYSQL_PASSWORD  MYSQL_DB

退出码
    0 = 正常完成（含"无需修复"）
    1 = 扫描发现乱码但未执行修复（dry-run）
    2 = 执行过程中出错
"""
import argparse
import json
import os
import subprocess
import sys
import time

MYSQL_BIN = os.environ.get("MYSQL_BIN", "mysql")
MYSQLDUMP_BIN = os.environ.get("MYSQLDUMP_BIN", "mysqldump")

HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")
PORT = os.environ.get("MYSQL_PORT", "3306")
USER = os.environ.get("MYSQL_USER", "root")
PASSWORD = os.environ.get("MYSQL_PASSWORD", "123456")
DATABASE = os.environ.get("MYSQL_DB", "echo")

# 常用汉字种子：用于自动推导"乱码特征字"，避免手写特征字表出错
SEED = (
    "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞"
)

# 需要扫描的业务字段：表、主键列、文本列
TARGETS = [
    ("tb_show", "id", "name"),
    ("tb_show", "id", "area"),
    ("tb_show", "id", "address"),
    ("tb_show", "id", "open_hours"),
    ("tb_show", "id", "description"),
    ("tb_ticket", "id", "title"),
    ("tb_ticket", "id", "sub_title"),
    ("tb_ticket", "id", "rules"),
    ("tb_show_type", "id", "name"),
    ("tb_show_review", "id", "title"),
    ("tb_show_review", "id", "content"),
    ("tb_show_review_comments", "id", "content"),
    ("tb_user", "id", "nick_name"),
    ("tb_user_info", "user_id", "city"),
    ("tb_user_info", "user_id", "introduce"),
    ("tb_notification", "id", "title"),
    ("tb_notification", "id", "content"),
    ("tb_points_record", "id", "description"),
    ("tb_coupon", "id", "name"),
    ("tb_admin", "id", "name"),
    ("tb_seat", "id", "area"),
]


def build_hint_chars():
    """由常用汉字推导乱码特征字：把常用汉字按 UTF-8 编码后当 GBK 解读"""
    raw = SEED.encode("utf-8")
    decoded = raw.decode("gbk", errors="ignore")
    return set(decoded)


HINT_CHARS = build_hint_chars()


def mysql_base_args():
    return [MYSQL_BIN, f"-h{HOST}", f"-P{PORT}", f"-u{USER}",
            f"--password={PASSWORD}", DATABASE, "--default-character-set=utf8mb4"]


def run_sql(sql):
    proc = subprocess.run(mysql_base_args() + ["-N", "-B", "-e", sql],
                          capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", "replace")[:300])
    return proc.stdout.decode("utf-8", "replace")


def restore(value):
    """乱码还原：错字按 GBK 取字节，再按 UTF-8 解码"""
    if not value or not value.strip():
        return None
    try:
        raw = value.encode("gbk")
    except UnicodeEncodeError:
        return None
    try:
        fixed = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if fixed == value or not fixed.strip():
        return None
    if not any("\u4e00" <= ch <= "\u9fff" for ch in fixed):
        return None
    return fixed


def is_mojibake(value):
    """
    判定是否乱码：还原成立，且原串含乱码特征字。
    特征字这一层是可选的加固，用来抵挡"正常中文恰好满足往返"的极小概率误判。
    """
    fixed = restore(value)
    if fixed is None:
        return None
    cjk = sum(1 for ch in value if "\u4e00" <= ch <= "\u9fff")
    if cjk < 2:
        return None
    if not any(ch in HINT_CHARS for ch in value):
        return None
    return fixed


def scan():
    findings = []
    for table, pk, column in TARGETS:
        try:
            out = run_sql(
                f"SELECT `{pk}`, `{column}` FROM `{table}` "
                f"WHERE `{column}` IS NOT NULL AND `{column}` <> '';")
        except RuntimeError as exc:
            findings.append({"table": table, "column": column, "error": str(exc)})
            continue

        rows = []
        total = 0
        for line in out.splitlines():
            if "\t" not in line:
                continue
            total += 1
            rid, value = line.split("\t", 1)
            fixed = is_mojibake(value)
            if fixed is not None:
                rows.append({"pk": rid, "before": value, "after": fixed})

        findings.append({
            "table": table, "pk": pk, "column": column,
            "scanned": total, "broken": len(rows), "rows": rows,
        })
    return findings


def backup(findings, stamp):
    tables = sorted({f["table"] for f in findings if f.get("broken")})
    if not tables:
        return None
    backup_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backup")
    os.makedirs(backup_dir, exist_ok=True)
    path = os.path.join(backup_dir, f"before-mojibake-{stamp}.sql")

    args = [MYSQLDUMP_BIN, f"-h{HOST}", f"-P{PORT}", f"-u{USER}",
            f"--password={PASSWORD}", "--default-character-set=utf8mb4",
            "--single-transaction", "--no-tablespaces", DATABASE] + tables
    with open(path, "wb") as fh:
        proc = subprocess.run(args, stdout=fh, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise RuntimeError("备份失败: " + proc.stderr.decode("utf-8", "replace")[:300])
    return path


def apply(findings, stamp):
    statements = []
    for item in findings:
        for row in item.get("rows", []):
            hex_literal = row["after"].encode("utf-8").hex().upper()
            statements.append(
                f"UPDATE `{item['table']}` SET `{item['column']}` = CONVERT(X'{hex_literal}' USING utf8mb4) "
                f"WHERE `{item['pk']}` = '{row['pk']}';")

    if not statements:
        return 0

    sql_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"_repair-{stamp}.sql")
    with open(sql_path, "w", encoding="utf-8") as fh:
        fh.write("SET NAMES utf8mb4;\nSTART TRANSACTION;\n")
        fh.write("\n".join(statements))
        fh.write("\nCOMMIT;\n")

    try:
        with open(sql_path, "rb") as fh:
            proc = subprocess.run(mysql_base_args(), stdin=fh,
                                  capture_output=True)
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.decode("utf-8", "replace")[:300])
    finally:
        # 临时 SQL 里含明文昵称；删除失败不阻断修复流程，仅提示手工清理
        try:
            if os.path.exists(sql_path):
                os.remove(sql_path)
        except OSError:
            print(f"提示：临时 SQL 未能自动删除，可手工删除 {sql_path}")

    return len(statements)


def main():
    parser = argparse.ArgumentParser(description="清理 UTF-8/GBK 混存的历史乱码")
    parser.add_argument("--apply", action="store_true", help="执行修复（默认只扫描）")
    parser.add_argument("--yes", action="store_true", help="跳过交互确认")
    parser.add_argument("--max-sample", type=int, default=3, help="每列打印的样例条数")
    args = parser.parse_args()

    print(f"目标库: {USER}@{HOST}:{PORT}/{DATABASE}")
    print(f"特征字表: {len(HINT_CHARS)} 个\n")

    findings = scan()

    header = f"{'表':<26}{'字段':<18}{'扫描':>7}{'乱码':>7}"
    print(header)
    print("-" * len(header))
    total_broken = 0
    for item in findings:
        if "error" in item:
            print(f"{item['table']:<26}{item['column']:<18}  跳过: {item['error'][:60]}")
            continue
        total_broken += item["broken"]
        print(f"{item['table']:<26}{item['column']:<18}{item['scanned']:>7}{item['broken']:>7}")
        for row in item["rows"][: args.max_sample]:
            print(f"    id={row['pk']}  {row['before'][:34]!r} -> {row['after'][:34]!r}")

    print(f"\n合计待修复: {total_broken} 条")

    if total_broken == 0:
        print("无需修复。")
        return 0

    if not args.apply:
        print("\n当前为扫描模式，数据库未发生变更。执行修复请追加 --apply")
        return 1

    if not args.yes:
        answer = input(f"\n即将修改 {total_broken} 条记录，执行前会自动备份。输入 yes 继续: ")
        if answer.strip().lower() != "yes":
            print("已取消，数据库未发生变更。")
            return 1

    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup_path = backup(findings, stamp)
    print(f"\n备份文件: {backup_path}")

    changed = apply(findings, stamp)
    print(f"已修复: {changed} 条")

    log_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            f"repair-log-{stamp}.json")
    with open(log_path, "w", encoding="utf-8") as fh:
        json.dump({"stamp": stamp, "backup": backup_path,
                   "changed": changed, "findings": findings}, fh,
                  ensure_ascii=False, indent=2)
    print(f"变更明细: {log_path}")

    print("\n复核（应为 0）:")
    recheck = scan()
    remain = sum(i["broken"] for i in recheck if "broken" in i)
    print(f"  残留乱码: {remain} 条")
    return 0 if remain == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
