# minisYf

[OpenMinis](https://github.com/OpenMinis/OpenMinis) 1.15 的改造版。GPL-3.0，上游全部能力保留，人格与品牌换成本机自用配置。

- upstream: `OpenMinis/OpenMinis`（`git remote add upstream` 后可随时 `git merge` 同步）
- 与原版**可并存安装**：包名不同

## 改了什么

| 项目 | 上游 | minisYf |
|---|---|---|
| 应用名 | Minis | minisYf |
| Android 包名 | `com.openminis.app` | `com.minisyf.app` |
| iOS Bundle ID | `com.openminis.app` | `com.minisyf.app` |
| SOUL.md 默认正文 | 三行英文建议，正文为空 | 预置御风人格（Android `SoulStore.DEFAULT_CONTENT` / iOS `SoulStore.defaultContent`） |
| 人格长度上限 | 1600 字 / 1000 词（iOS 2000 token） | 30000 字 / 20000 词 |
| SOUL.md 注入过滤 | 删除含「ignore previous instructions」的行 | 关闭（`INJECTION_PATTERNS = emptyList()` / `scrubInjections` 原样返回） |
| 自更新仓库 | `OpenMinis/OpenMinis` | 本仓 `RELEASE_REPO` |
| release 签名 | debug 签名 | 自有 keystore（`MINISYF_KEYSTORE_PATH` 注入，缺省回落 debug） |

为什么要放开上限、关掉过滤：上游这两个机制是给「陌生用户共用一个 app」设计的——限制人格篇幅防滥用，过滤注入防第三方劫持 SOUL.md。自用场景下 SOUL.md 只有一个作者，过滤的唯一效果是把合法人格里带那几种句式的句子**静默删掉**（`scrubInjections` 不报错），上限的唯一效果是把完整人格判超限后**静默退回 identity-only**（人格整段不生效）。两个都是很难察觉的静默降级，所以改掉。

## 保留的机制（值得知道）

- **persona reminder**：上下文长到阈值时，`ChatViewModel.personaReminderText()` 会往历史里插 `<system-reminder>` 提醒模型「别忘了 SOUL.md / GLOBAL.md」。跟 Hermes 的每轮角色锚同一个思路，上游自带。
- **GLOBAL.md**：`/var/minis/memory/GLOBAL.md`，无篇幅上限，和 SOUL.md 一起进系统提示。人格写不下就往这里塞。
- **子 agent 不继承 SOUL**：`HelperRunner.identitySection()` 明确写「这是调用环境，不是你的身份」，深度 1 的 helper 拿到的是环境描述。想让子任务也按同一套行为准则干活，得自己往那儿注入。
- **技能**：`skills/<id>/SKILL.md`，`git clone` 进 skillsDir 即可，不用重新编译。

## 构建

### GitHub Actions（推荐）

`.github/workflows/build-minisyf.yml`，手动触发：

```bash
gh workflow run build-minisyf.yml -f version_name=1.15-yf2 -f create_release=true
```

需要的 secrets：

| Secret | 内容 |
|---|---|
| `MINISYF_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `MINISYF_STORE_PASSWORD` | keystore 密码 |
| `MINISYF_KEY_PASSWORD` | key 密码 |

流程：SDK 36 + NDK + CMake → `deps/build_proot.sh` → `prepare_android_sandbox.sh` → gomobile 编 `rclone.aar` → `assembleGithubRelease` → `apksigner verify` → artifact + Release。

### 本地

```bash
# 前置：JDK 21、Android SDK(platform 36 / build-tools 36 / cmake 3.22.1)、NDK r28+
# 关键：Debian/Ubuntu 默认 awk 是 mawk，proot 的 loader-info.awk 用了 gawk 的 strtonum
sudo apt-get install -y gawk && sudo update-alternatives --set awk /usr/bin/gawk

git clone --recurse-submodules <this repo> && cd <repo>
cp -n src/android/app/provider-customization.properties.example \
      src/android/app/provider-customization.properties

export ANDROID_HOME=... ANDROID_NDK_HOME=...
bash deps/build_proot.sh              # → assets/proot-aarch64, jniLibs/arm64-v8a/libproot*.so
bash scripts/prepare_android_sandbox.sh   # → assets/alpine-minirootfs.tar.gz
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
bash deps/build_rclone_android.sh     # → deps/build/rclone/rclone.aar
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/

cd src/android && ./gradlew :app:assembleGithubRelease
```

产物：`src/android/app/build/outputs/apk/github/release/*.apk`

## 踩过的坑

1. **`awk: function strtonum never defined`** —— Debian/Ubuntu 默认 mawk。装 gawk 并把 `awk` 指过去，否则 `build_proot.sh` 在 loader 阶段直接中断（前面的 talloc 已经编好了，看起来像“快成功了”）。
2. **NDK 版本**：`build.gradle.kts` 没写 `ndkVersion`，AGP 8.7.3 会自动装 NDK 27；`deps/build_proot.sh` 用 `$ANDROID_NDK_HOME`，两者可以不同版本共存。
3. **`assembleRelease` 会同时编 github / store 两个 flavor** —— 要指定 `assembleGithubRelease`，否则 `find` 可能拿到 store 的包。
4. **自更新**：`RELEASE_REPO` 不改的话，装完点「检查更新」会去拉原版 APK，签名不同装不上，报错还看不出原因。
5. **签名必须一致**：后续版本换 keystore 就得先卸载重装，`applicationId` 相同的情况下无法覆盖安装。

## 许可证

GPL-3.0，继承自上游。修改分发需同样以 GPL-3.0 开源并保留上游版权声明。
