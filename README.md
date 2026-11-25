# CapBridge

没有 ADB、没有 root 时，把 Android 能给的能力全部接出来，统一成一套本机 HTTP 接口。

## 为什么不是 Shizuku

Shizuku 靠 ADB 拿 shell（uid 2000）。华为 EMUI 的开发者选项里没有「无线调试」，USB 也不常连电脑 —— 这一条路整条断掉。

但 Android 还有一批**完全不需要 ADB** 的通道，只是从来没人把它们接在一起：

| 通道 | 授权方式 | 能给什么 |
|---|---|---|
| 无障碍服务 | 设置里点一次 | 读当前界面树、点击、滑动、输入、全局动作 |
| 通知访问 | 设置里点一次 | 所有应用的通知流 |
| MediaProjection | 弹窗一次 | 全屏截图（未实现） |
| MANAGE_EXTERNAL_STORAGE | 设置里点一次 | 整个 /sdcard 读写（未实现） |

CapBridge 先接前两条，因为它们覆盖了「看屏幕 + 操作屏幕」这件事——这正好是 agent 最缺的。

## 它做不到什么

诚实列出硬边界，这些不是实现问题，是 SELinux 域隔离：

- 读其他应用的 `/data/data/` 私有目录
- ptrace 其他进程、注入内存
- 修改系统分区

## 安装

1. 从 Actions 的 artifact 下载 `CapBridge-debug.apk`
2. 安装（允许未知来源）
3. 打开，依次点「开启无障碍」「开启通知访问」
4. 点「启动本机桥」，状态页会显示 `127.0.0.1:8789 在线`

## 接口

只绑 `127.0.0.1`。局域网内其他设备连不上。

```
GET  /probe                     能力矩阵 + 盲区清单
GET  /screen?depth=6            当前界面树（JSON，带屏幕坐标）
GET  /notifications             最近 50 条通知
POST /tap    {"x":540,"y":1200}
POST /swipe  {"x1":..,"y1":..,"x2":..,"y2":..,"ms":300}
POST /text   {"text":"hello"}
POST /key    {"action":"back"}   back/home/recents/notifications
```

`/screen` 返回的每个节点带 `b:[left,top,right,bottom]`，直接喂给 `/tap` 就能点中，不需要换算。

## 例子

```bash
curl -s http://127.0.0.1:8789/probe
curl -s http://127.0.0.1:8789/screen?depth=4
curl -s -X POST http://127.0.0.1:8789/tap -d '{"x":540,"y":1200}'
```

## 构建

推上去由 GitHub Actions 打包，产物在 Actions 页面的 artifact 里。

```bash
gradle assembleDebug
```

## License

MIT
