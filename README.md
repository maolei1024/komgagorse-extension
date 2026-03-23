# Komga Gorse Extension

基于 [Gorse](https://gorse.io/) 推荐引擎的 [Komga](https://komga.org/) Mihon 扩展插件。

## 功能

- 在 Mihon 中通过「热门」标签页浏览 Gorse 推荐的漫画
- 支持多服务器实例（最多 3 个）
- 与官方 Komga 扩展共存

## 安装

在 Mihon 中添加扩展仓库：

```
https://raw.githubusercontent.com/maolei1024/komgagorse-extension/master/repo/index.min.json
```

## 版本号

使用基于 UTC 时间戳的自动版本号：
- `versionCode`: `YYMMDDHHMM` 格式
- `versionName`: `YYYY.MMDD.HHMM` 格式

## 本地构建

```bash
# 需要先 clone keiyoushi/extensions-source
git clone https://github.com/keiyoushi/extensions-source.git ~/code/extensions-source

# 构建
./build-extension.sh
```

## 许可

MIT
