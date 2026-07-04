# 鲲穹会纪 iOS 云端打包说明

本文档记录鲲穹会纪原生 iOS 工程的云端真机包构建方式。当前目标是用 GitHub Actions 的 macOS runner 生成可上传 TestFlight 的 `.ipa`，避免依赖本地 macOS 电脑。

## 当前苹果侧信息

- App 名称：鲲穹会纪
- Bundle ID：`com.huiyi.app.ios`
- Team ID：`G4C3ADW2F4`
- Profile 名称：`Huiyi App Store Connect`
- App Store Connect API Key ID：`6MH32YCU44`

## 本地签名材料

本地签名材料保存在仓库根目录的 `local_secrets/ios_signing/`，该目录已被 `.gitignore` 忽略，禁止提交。

需要保留的文件：

- `huiyi_ios_distribution.p12`
- `huiyi_ios_distribution_p12_password.txt`
- `Huiyi_App_Store_Connect.mobileprovision`
- `AuthKey_6MH32YCU44.p8`

## GitHub Secrets

在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中新增：

- `IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64`
- `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`
- `IOS_PROVISIONING_PROFILE_BASE64`
- `APP_STORE_CONNECT_API_KEY_P8_BASE64`
- `APP_STORE_CONNECT_KEY_ID`
- `APP_STORE_CONNECT_ISSUER_ID`

其中：

- `APP_STORE_CONNECT_KEY_ID` 填 `6MH32YCU44`
- `APP_STORE_CONNECT_ISSUER_ID` 从 App Store Connect 的 API Keys 页面复制
- `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` 填本地 `huiyi_ios_distribution_p12_password.txt` 里的内容

PowerShell 生成 base64 值示例：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("F:\会晓AI\local_secrets\ios_signing\huiyi_ios_distribution.p12")) | Set-Clipboard
[Convert]::ToBase64String([IO.File]::ReadAllBytes("F:\会晓AI\local_secrets\ios_signing\Huiyi_App_Store_Connect.mobileprovision")) | Set-Clipboard
[Convert]::ToBase64String([IO.File]::ReadAllBytes("F:\会晓AI\local_secrets\ios_signing\AuthKey_6MH32YCU44.p8")) | Set-Clipboard
```

每次执行一条命令，然后把剪贴板内容粘贴到对应 GitHub Secret。

## 运行方式

GitHub Actions 页面手动运行：

```text
iOS Device Build
```

参数：

- `upload_testflight=false`：只构建 `.ipa` 并上传 artifact。
- `upload_testflight=true`：构建 `.ipa` 后上传 TestFlight。

建议首次运行先选择 `false`，确认能编译和导出 `.ipa` 后，再选择 `true` 上传 TestFlight。

## 注意事项

- 该 workflow 只构建 `apps/ios_app`。
- 不修改 Android、服务端或数据库。
- 签名材料、开发者账号、API Key 不得提交到仓库。
- GitHub public 仓库可减少 Actions 额度压力，但公开前必须确认仓库没有敏感代码和历史密钥。
