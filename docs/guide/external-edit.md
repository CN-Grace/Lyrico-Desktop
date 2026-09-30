# 外部编辑接入

播放器可通过 Intent 打开 Lyrico 的单曲编辑页，修改音频标签、歌词和封面。

## 1. 准备文件

取得原始音频的 `content://` URI（MediaStore、SAF 或 FileProvider），确保可读取并以 `rw` 模式打开可定位的文件描述符。自有 Provider 需支持 URI 授权及原文件读写。

## 2. 启动编辑

从 Activity 调用，`audioUri` 为上一步取得的 URI：

```kotlin
val intent = Intent("com.lonx.lyrico.action.EDIT_TAG").apply {
    setPackage("com.lonx.lyrico")
    setDataAndType(audioUri, "audio/*")
    clipData = ClipData.newRawUri("audio", audioUri)
    addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
}
startActivity(intent)
```

未安装 Lyrico 时，捕获 `ActivityNotFoundException` 并提示安装。测试 Debug 版时，包名改为 `com.lonx.lyrico.debug`。

## 3. 返回后刷新

用户点击“保存”后，Lyrico 写入文件并退出编辑页。播放器返回前台时，应重新读取本次编辑歌曲的标签、歌词和封面，并更新缓存。

当前接口不返回保存结果，不能通过 Activity Result 判断是否保存成功。

接入后验证打开、保存、取消及返回刷新流程。如需加入 [已适配列表](https://github.com/Replica0110/Lyrico#已适配-lyrico-外部编辑功能的播放器)，请提交 PR，注明播放器名称、链接和最低适配版本。
