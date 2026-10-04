# Sonify 云更新发布

默认检查地址：`https://raw.githubusercontent.com/XiaoZhi0729/Sonify/master/updates/latest.json`。
APK 地址：`https://github.com/XiaoZhi0729/Sonify/releases/download/<tag>/<name>.apk`。

这里只提供本地生成工具、资源和示例。`updates/example.json` 是不可发布的格式示例，APK 大小和 SHA-256 为占位值；`updates/generated.json` 是待审核产物。`latest.json` 只能在对应 APK 已上传并完成线上校验后手动发布，不能把示例或本地生成产物直接当作线上清单。

## 清单约定

清单为 UTF-8 JSON；`schemaVersion` 固定为整数 `1`，不添加强制更新字段。

| 字段 | 类型与含义 |
| --- | --- |
| `applicationId` | APK 中的包名，本项目为 `com.sonify.music` |
| `versionCode` | 正整数，从已签名 APK 提取，必须大于已安装版本和此前发布版本 |
| `versionName` | APK 中的显示版本名称；不能用它判断版本先后 |
| `publishedAt` | ISO 8601 时间，生成工具使用 UTC；延迟发布时重新生成 |
| `minSdk` | 正整数，从 APK 提取，设备必须满足 |
| `abis` | 非空字符串数组，从 APK 内 `lib/<abi>/*.so` 提取；只接受 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`，最多四项。当前客户端不接受无 native 库的空数组 |
| `apkUrl` | 对应 Tag 的公开 GitHub Release APK 附件 HTTPS 地址 |
| `apkSize` | APK 的准确字节数，JSON 整数，不是格式化大小字符串 |
| `sha256` | APK 文件 SHA-256，64 位小写十六进制字符串，不是签名证书摘要 |
| `releasePageUrl` | 同一 Tag 的 GitHub Release 页面 HTTPS 地址 |
| `releaseNotes` | 四语言对象：`en`、`zh-CN`、`zh-TW`、`ja` 各对应字符串数组；无说明可用 `[]` |

更新服务使用单通道：客户端读取清单，不通过 GitHub `/releases/latest` 选择版本。因此公开的 **pre-release 也能推送给该通道的所有符合条件的用户**；标为 pre-release 并不能把更新隔离为测试通道。草稿或私有附件不能用于公共更新。每次发布都要严格递增 `versionCode`，包括修复包、预发布和从预发布转正式版；不能降低它来回滚，回滚修复也要发更高的版本号。项目版本来源是根目录 `gradle.properties` 的 `APP_VERSION_CODE` / `APP_VERSION_NAME`，工具最终以 APK 为准，不修改这些文件。

## 本地生成

使用 Windows PowerShell 5.1 或更高版本。需要已安装的 Android SDK、可运行 SDK Java 工具的 Java 环境，以及一个**已经签名、可独立安装的完整 APK**。工具不构建、不签名、不下载 SDK，也不自动上传。

必填参数为 `-ApkPath`、`-Tag`、`-NotesJsonPath`。`-SdkPath` 默认 `D:\Android\Sdk`，`-OutputPath` 默认项目内 `updates/generated.json`，建议调用时显式给出；工具拒绝任何名为 `latest.json` 的输出。`Tag` 必须使用字母、数字、点、下划线或连字符，首字符为字母或数字。APK 文件名也限定为这些 URL 安全 ASCII 字符，必须使用小写 `.apk` 扩展名；本地文件名就是附件名，上传时必须保持完全一致。

生成器与客户端限制一致：APK 最多 200 MiB，最终 UTF-8 清单最多 256 KiB，`versionName` 最多 128 字符，`minSdk` 为 1 至 10000；ABI 非空且仅限表中四种。每种语言最多 100 条说明，每条最多 4096 字符且不能全为空白；语言条目本身可以是空数组。

准备一个 UTF-8 发行说明 JSON 文件，注意它是语言 map，不是完整清单：

```json
{
  "en": ["Add cloud updates."],
  "zh-CN": ["新增云更新。"],
  "zh-TW": ["新增雲更新。"],
  "ja": ["クラウド更新を追加。"]
}
```

从项目根目录运行（替换 APK、Tag、说明文件和证书摘要为真实值）：

```powershell
.\tools\generate_update_manifest.ps1 `
  -ApkPath 'D:\releases\Sonify-v1.2.3.apk' `
  -Tag 'v1.2.3' `
  -NotesJsonPath 'D:\releases\notes-v1.2.3.json' `
  -SdkPath 'D:\Android\Sdk' `
  -OutputPath '.\updates\generated.json' `
  -ExpectedSignerSha256 '<可信历史版本的签名证书SHA-256，64位十六进制>'
```

`-ExpectedSignerSha256` 可选，但正式发布应使用从可信历史 APK 或受控签名记录取得的证书摘要，而不是从本次待发布 APK 自己取得后再自证。省略此参数只证明 APK 签名有效，**不证明签名与用户已装版本连续**。`-AllowDebugSigning` 默认关闭，仅供已发布 debug-key 版本的签名连续性过渡使用；启用时必须同时提供非空 `-ExpectedSignerSha256`，且本次 APK 的唯一签名证书摘要必须与它匹配。摘要缺失、不匹配或签名验证失败均不会输出清单。

工具选取 SDK 中包含 `aapt2.exe` 和 `apksigner.bat` 的最高稳定版 build-tools。先通过 `apksigner verify --verbose --print-certs` 验证签名并显示证书 SHA-256，再通过 `aapt2 dump badging` 读取包名、版本号和最低 SDK；若已安装 `cmdline-tools/*/bin/apkanalyzer.bat` 或旧版 `tools/bin/apkanalyzer.bat`，还会交叉核对这些字段。不要求安装 `apkanalyzer`，但已安装工具运行失败或给出矛盾结果时会终止。

工具默认拒绝常见 Android Debug 证书、错误包名、split APK、无效版本、错误语言 map、未知 ABI 和签名不匹配。仅在明确的历史 debug-key 迁移场景下，使用 `-AllowDebugSigning` 并同时提供匹配的可信 `-ExpectedSignerSha256`；此开关不是安全背书，也不应作为正常 release 流程。使用读取锁保护 APK，计算实际字节数和 SHA-256，全部校验通过才以 UTF-8 无 BOM 原子写出清单。失败返回非零退出码，不写伪造数据；已有输出保持不变，**不得误把旧输出当成本次成功产物**。脚本不核实线上 URL，也不自动判断远端版本递增，发布者需完成下面的核验。

## 签名与首版引导

Android 覆盖安装要求相同 `applicationId`，并保持签名证书连续性。云更新不能绕过系统签名校验。保护发布 keystore、密码和备份，不将其或密钥写进仓库。未经完整兼容性验证，不应在此流程中轮换签名密钥。保留可信历史 APK 的证书摘要，通过 `apksigner verify --print-certs` 比对。

debug-key 即使通过签名验证也不适合公共发布：常见 debug 密钥可被他人获取或复现，不同开发环境还可能生成不同密钥。脚本对常见 `CN=Android Debug` 的默认拒绝不是对所有不安全密钥的完整检测。既有 debug-key 安装通常不能被新 release-key APK 覆盖，必须先备份数据、明确迁移方案；卸载重装可能丢失应用数据，不能把更换密钥称为无损更新。

若历史公开版本已经使用 debug key，为维持覆盖安装连续性，过渡期可在上述命令中显式加入 `-AllowDebugSigning`，并保留 `-ExpectedSignerSha256 '<可信历史版本证书摘要>'`。脚本必须验证摘要匹配才放行，并输出风险警告；不能省略摘要，也不能把不匹配的证书当作迁移成功。这个例外不降低 debug-key 被冒用的风险，不应扩展成长期正式发布策略。

旧版没有更新功能时，需要用户先从可信 Release 页面**手动下载并安装首个支持云更新的版本**。首版也应沿用用户已装版本的签名和包名；历史 debug-key 安装的过渡须满足上述显式例外条件。成功手动引导后，后续更高 `versionCode` 才能由应用检查、下载校验并调用系统安装器。调用安装器不等于安装成功，最终确认由 Android 完成。

本方案没有 `forceupdate` / 强制更新、WorkManager 后台更新或 Shizuku 静默安装；更新由用户确认，系统“允许来自此来源的应用”权限及安装界面仍需用户操作。

## 发布顺序

1. 准备已签名完整 APK，确认版本号递增、最低 SDK、ABI 与历史签名。生成 `updates/generated.json`，确认脚本退出码为 0，人工审阅所有字段与四语言说明。
2. **先上传 APK** 到清单对应 Tag 的 GitHub Release，保持附件名完全一致，并将 Release 设为公开。正式版或公开 pre-release 均可，不能仍处于 draft。
3. 从清单的 `apkUrl` 实际下载线上附件，而非复用本地 APK。验证下载成功，字节数和 SHA-256 与清单一致，再验证下载文件签名和元数据。不得只依据附件名称、浏览器缓存、重定向或服务器提供的摘要。
4. 全部核验通过后，才由发布者手动将审核后的清单作为 `master` 分支的 `updates/latest.json` 发布；这是激活步骤，脚本不执行。**必须先 APK、后 latest.json**，避免用户读到尚不可下载或哈希错误的版本。
5. 获取默认 raw 地址，确认 JSON 可公开访问、字段与已验证附件一致；考虑 GitHub raw/CDN 缓存，等待线上清单生效。再用较低版本设备验证检查、下载、大小/哈希校验、安装权限及系统安装器流程。不要在版本已被使用后替换同 URL 的 APK 内容。

以下示例只用于发布者主动核验线上文件，不会自动激活或上传；在项目根目录执行，下载路径放在系统临时目录：

```powershell
$manifest = Get-Content '.\updates\generated.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$onlineApk = Join-Path $env:TEMP ('sonify-release-check-' + [guid]::NewGuid().ToString('N') + '.apk')
try {
  Invoke-WebRequest -Uri $manifest.apkUrl -OutFile $onlineApk -UseBasicParsing -ErrorAction Stop
  if ((Get-Item $onlineApk).Length -ne $manifest.apkSize) { throw 'Online APK size mismatch' }
  if ((Get-FileHash $onlineApk -Algorithm SHA256).Hash -ine $manifest.sha256) {
    throw 'Online APK SHA-256 mismatch'
  }
  # Use the same SDK build-tools selected for local generation.
  & 'D:\Android\Sdk\build-tools\37.0.0\apksigner.bat' verify --verbose --print-certs $onlineApk
  if ($LASTEXITCODE -ne 0) { throw 'Online APK signature verification failed' }
  & 'D:\Android\Sdk\build-tools\37.0.0\aapt2.exe' dump badging $onlineApk
  if ($LASTEXITCODE -ne 0) { throw 'Online APK metadata extraction failed' }
  # Compare signer certificate, package name, version, SDK and ABI with the approved release.
} finally {
  Remove-Item -LiteralPath $onlineApk -Force -ErrorAction SilentlyContinue
}
```

清单的文件 SHA-256 用于完整性验证，不是清单本身的数字签名；仓库权限、GitHub 账号安全、HTTPS 和 Android 安装签名校验仍是信任边界。不能仅凭哈希声称发布来源可信。
