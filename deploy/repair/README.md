# 历史乱码清理工具

用于修复"UTF-8 字节被按 GBK 解读"形成的中文乱码。

## 乱码是怎么来的

```
"压测用户1"
  --UTF-8 编码-->  E5 8E 8B E6 B5 8B E7 94 A8 E6 88 B7 31
  --按 GBK 解读-->  "鍘嬫祴鐢ㄦ埛1"      ← 存进数据库的就是这串错字
```

典型来源：命令行客户端未设置 `--default-character-set=utf8mb4`、
连接串缺少 `characterEncoding=UTF-8`、压测工具或旧脚本以 GBK 写入。

## 还原原理

错字按 GBK 编码能取回原始字节，这批字节正好是正确的 UTF-8 编码。
两步都无损完成时还原成立，因此这个变换自带可逆校验，不存在"猜"的成分。

## 用法

```bash
# 扫描（默认，不写库）
python deploy/repair/fix-mojibake.py

# 备份后执行修复
python deploy/repair/fix-mojibake.py --apply
```

连接参数从环境变量读取，默认值对齐 `application.yaml`：

| 变量 | 默认值 |
|---|---|
| `MYSQL_BIN` | `mysql` |
| `MYSQLDUMP_BIN` | `mysqldump` |
| `MYSQL_HOST` | `127.0.0.1` |
| `MYSQL_PORT` | `3306` |
| `MYSQL_USER` | `root` |
| `MYSQL_PASSWORD` | `123456` |
| `MYSQL_DB` | `echo` |

`mysql` 与 `mysqldump` 需要在 PATH 中。

## 执行流程

1. 逐表逐列扫描，用"还原成立 + 命中乱码特征字"双重判据筛选，特征字由常用汉字自动推导
2. `--apply` 时先用 `mysqldump` 备份所有涉及的表到 `deploy/repair/backup/`
3. 在单个事务内按主键更新，值以 `X'...'` 十六进制字面量写入，规避转义问题
4. 变更明细落盘到 `deploy/repair/repair-log-<时间戳>.json`
5. 修复后立即复扫，残留数写入退出码

## 涉及字段

`tb_show`、`tb_ticket`、`tb_show_type`、`tb_show_review`、`tb_show_review_comments`、
`tb_user.nick_name`、`tb_user_info`、`tb_notification`、`tb_points_record`、
`tb_coupon`、`tb_admin`、`tb_seat`。

字段清单在脚本顶部的 `TARGETS` 中维护，新增文本列时补充即可。

## 退出码

| 退出码 | 含义 |
|---|---|
| 0 | 完成，或无需修复 |
| 1 | 扫描到乱码但未修复（扫描模式） |
| 2 | 执行过程出错，或修复后仍有残留 |

## 注意事项

- 备份文件与修复日志含真实业务数据，已加入 `.gitignore`
- 数据修复后需要清一次业务缓存，避免旧值从 Redis 读回
- 修复只改变字符内容，不改字符集；仍然建议确认表与连接均为 `utf8mb4`
