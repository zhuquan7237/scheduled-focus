# 参与开发

请在 Issue 中附上设备型号、Android / HyperOS 版本、应用版本、复现步骤、预期与实际结果。不要附带私人聊天、支付信息或密钥。

需要 JDK 17 和 Android SDK 35。修改后运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`。设备集成测试只允许模拟器执行自动设置更改：`./gradlew connectedDebugAndroidTest`。

时间规则和锁定必须由存储层执行。不要添加临时放行或提前结束入口；系统必要交互不能被误拦。新增更新渠道时必须保留包名、签名与版本校验。

发布时增加 versionCode 和 versionName，更新 `docs/RELEASE_NOTES.md`，推送与 versionName 一致的 `vX.Y.Z` 标签。签名工作流使用仓库加密 Secrets；私钥永远不能加入 Git。模拟器通过后仍需记录真机未验证项目，不能宣称已经通过。
