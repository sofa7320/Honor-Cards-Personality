Copyright 2026 The CardPersonality Project contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

# 第三方组件与许可声明（Third-Party Notices）

药做卡面（Card Personality）在构建与运行时使用/参考了以下第三方组件。
分发本项目的 APK 或源码时，请保留本文件及相应许可证声明。

## 运行时依赖

| 组件 | 用途 | 许可证 |
|---|---|---|
| [Miuix](https://github.com/compose-miuix-ui/miuix)（`top.yukonga.miuix.kmp:miuix-ui`、`miuix-icons`、`miuix-preference`、`miuix-blur`） | 界面组件与设计规范 | Apache License 2.0 |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack)（core-ktx、activity-compose、lifecycle、compose-ui/foundation/runtime、material3） | Android/Compose 基础库 | Apache License 2.0 |
| [Kotlin Standard Library](https://kotlinlang.org/) | Kotlin 运行时 | Apache License 2.0 |

## 构建期依赖（不随 APK 分发）

| 组件 | 用途 | 许可证 |
|---|---|---|
| [libxposed API](https://github.com/libxposed/api)（`compileOnly`，不打包进 APK） | 现代 Xposed 模块 API 102 | Apache License 2.0 |
| Android Gradle Plugin / Gradle / Kotlin Compose Compiler | 构建工具链 | Apache License 2.0 / Gradle 为 Apache-2.0、Kotlin 为 Apache-2.0，以各自项目为准 |

## 运行环境（不随本项目分发）

| 组件 | 说明 | 许可证 |
|---|---|---|
| [LSPosed / Vector](https://github.com/JingMatrix/LSPosed) | 模块加载框架（用户自行安装的独立项目，本项目不包含其代码） | GPL-3.0（仅框架自身，本项目未链接/分发其代码） |
| 荣耀钱包（HONOR Wallet） | 被注入的目标应用，本项目不包含其任何二进制或资源文件 | 闭源，版权归荣耀所有 |

## 字体与图片

- 项目**不包含**任何商业字体：字体列表均引用用户设备系统字体或用户自行导入的字体文件。
- 示例卡面图片（`app/src/main/res/raw/sample_face.jpg`）为项目自带示例素材，仅用于演示；用户替换卡面所用图片由用户自行提供，其版权与合规责任由用户承担。

如有遗漏或异议，请提交 Issue 联系维护者。
