# 会晓 AI Android Demo

真实安卓端前端 Demo，路径：`F:\会晓AI\apps\android_app`。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Android 原生工程

## 当前范围

- 本地数据 + 后端 API 调用
- 首页、实时记录、会议详情、待办、知识库、我的
- 底部导航、开始记录确认、结束记录、详情分段、来源核验、分享导出、删除确认
- 已接入后端 API、文件上传处理、实时转写 WebSocket、知识库问答

## 构建与运行

当前已补充 Gradle Wrapper。Android SDK 安装在本项目目录的 `.android_sdk`，`local.properties` 指向该本地 SDK，不依赖全局 Android SDK。

项目提供两个环境 flavor：

- `local`：保留给需要双 App 隔离数据时使用，默认访问宿主机 `10.0.2.2:8080`。
- `staging`：当前默认只安装这一个 App，默认访问测试服务器 `43.154.197.96:28080`。日常本机联调必须用构建参数显式覆盖到 `10.0.2.2`；提测验收只能在测试负责人开放槽位后使用测试服务器地址。

本地模拟器调试（单 App，安装包名为 `com.huiyi.app`）：

```powershell
cd F:\会晓AI
start_local_services.bat
cd F:\会晓AI\apps\android_app
$env:GRADLE_USER_HOME='F:\会晓AI\apps\android_app\.gradle_home'
$env:ANDROID_SDK_ROOT='F:\会晓AI\apps\android_app\.android_sdk'
$env:ANDROID_HOME='F:\会晓AI\apps\android_app\.android_sdk'
.\gradlew.bat assembleStagingDebug `
  -PHUIXIAO_STAGING_API_BASE_URL=http://10.0.2.2:8080/api/v1 `
  -PHUIXIAO_STAGING_LIVE_WS_URL=ws://10.0.2.2:8080/api/v1/live/ws
```

真机连本机开发服务时，把地址改成电脑的局域网 IP：

```powershell
.\gradlew.bat assembleStagingDebug `
  -PHUIXIAO_STAGING_API_BASE_URL=http://192.168.1.10:8080/api/v1 `
  -PHUIXIAO_STAGING_LIVE_WS_URL=ws://192.168.1.10:8080/api/v1/live/ws
```

测试服打包仅在测试负责人申请并开放槽位后执行。当前测试服容器已关闭且暂不允许开启；未开放槽位时不要用该地址判断 App 逻辑异常。

```powershell
.\gradlew.bat assembleStagingDebug `
  -PHUIXIAO_STAGING_API_BASE_URL=http://43.154.197.96:28080/api/v1 `
  -PHUIXIAO_STAGING_LIVE_WS_URL=ws://43.154.197.96:28080/api/v1/live/ws
```

APK 输出示例：

```text
F:\会晓AI\apps\android_app\app\build\outputs\apk\local\debug\app-local-debug.apk
F:\会晓AI\apps\android_app\app\build\outputs\apk\staging\debug\app-staging-debug.apk
```

## 正式签名与支付平台信息

正式包名固定为 `com.huiyi.app`。支付平台 Android 信息固定使用：

```text
应用包名：com.huiyi.app
应用签名：8531D2343D10604A463ECC0C5DC6A459
```

正式签名文件在本机路径：

```text
F:\会晓AI\apps\android_app\signing\huiyi-release.jks
```

`signing/` 目录已加入 `.gitignore`，不要提交 keystore 或密码文件。以后所有正式 APK 必须继续使用同一个 `huiyi-release.jks`；只要包名和 keystore 不变，支付平台填写的包名和签名就不会变。换电脑打包时，要先把整个 `signing/` 目录安全复制到新机器同一路径或按 `signing/keystore.properties` 指向的位置放好。

本机已额外备份固定签名资料：

```text
F:\huixiao_tools\huiyi_signing_backup\huiyi-release.jks
F:\huixiao_tools\huiyi_signing_backup\keystore.properties
```

## 支付宝支付配置

支付宝秘钥资料已备份在：

```text
F:\huixiao_tools\huiyi_payment_backup\
```

服务端支付只从环境变量或秘钥文件读取配置，不要把应用私钥提交到 Git。当前已支持的服务端配置项：

```text
HUIXIAO_ALIPAY_APP_ID=
HUIXIAO_ALIPAY_PRIVATE_KEY_PATH=F:\huixiao_tools\huiyi_payment_backup\alipay_app_private_key.pem
HUIXIAO_ALIPAY_PUBLIC_KEY_PATH=F:\huixiao_tools\huiyi_payment_backup\alipay_public_key.pem
HUIXIAO_ALIPAY_NOTIFY_URL=https://你的服务域名/api/v1/payments/alipay/notify
HUIXIAO_ALIPAY_GATEWAY_URL=https://openapi.alipay.com/gateway.do
HUIXIAO_ALIPAY_AES_KEY=
```

当前支付宝开放平台应用 ID：

```text
HUIXIAO_ALIPAY_APP_ID=2021006161621675
```

当前测试服 API 地址是 `http://43.154.197.96:28080/api/v1`，所以测试服支付回调地址应配置为：

```text
HUIXIAO_ALIPAY_NOTIFY_URL=http://43.154.197.96:28080/api/v1/payments/alipay/notify
```

注意：`HUIXIAO_ALIPAY_NOTIFY_URL` 必须是支付宝能访问到的公网地址。本机 `127.0.0.1`、`localhost`、`10.0.2.2` 都不能作为支付宝异步通知地址。测试服只有部署包含 `/api/v1/payments/alipay/notify` 的新版服务后，异步通知才会真正生效。

如果支付宝开放平台启用了“接口内容加密方式”，服务端必须同步配置 `HUIXIAO_ALIPAY_AES_KEY`。这个配置只影响服务端生成订单串和调用支付宝 OpenAPI，Android 端不需要保存 AES 密钥。

正式测试服包构建命令：

```powershell
cd F:\会晓AI\apps\android_app
$env:GRADLE_USER_HOME='F:\会晓AI\apps\android_app\.gradle_home'
$env:ANDROID_SDK_ROOT='F:\会晓AI\apps\android_app\.android_sdk'
$env:ANDROID_HOME='F:\会晓AI\apps\android_app\.android_sdk'
.\gradlew.bat :app:assembleStagingRelease
```

正式 APK 输出：

```text
F:\会晓AI\apps\android_app\app\build\outputs\apk\staging\release\app-staging-release.apk
```

验包命令：

```powershell
$apk='F:\会晓AI\apps\android_app\app\build\outputs\apk\staging\release\app-staging-release.apk'
$tmp=Join-Path $env:TEMP 'huiyi-staging-release.apk'
Copy-Item -LiteralPath $apk -Destination $tmp -Force
& 'F:\会晓AI\apps\android_app\.android_sdk\build-tools\36.0.0\aapt.exe' dump badging $tmp | Select-String -Pattern "package:|application-label:"
& 'F:\会晓AI\apps\android_app\.android_sdk\build-tools\36.0.0\apksigner.bat' verify --print-certs $tmp
```

验包结果必须包含：

```text
package name='com.huiyi.app'
application-label:'鲲穹会纪'
Signer #1 certificate MD5 digest: 8531d2343d10604a463ecc0c5dc6a459
```

如果用 Android Studio，打开 `F:\会晓AI\apps\android_app` 即可同步工程。由于项目路径包含中文，已在 `gradle.properties` 设置 `android.overridePathCheck=true`。
