package com.ntu.schedule.core

/**
 * 统一身份认证（CAS）的登录结果。
 *
 * 单独成文件而不是塞在 [CasAuthenticator] 里，是为了让「登录」这件事的**结果**与
 * 「怎么登」的实现分开：上层（`ScheduleRepository`）只关心
 * 「成功没有 / 是不是密码错 / 是不是要验证码」，不关心内部走了几次重定向。
 */
sealed class AuthResult {
    /** 登录成功，可开始调用教务接口。[landingUrl] 是最终落到的页面，用于日志与排查。 */
    data class Success(val landingUrl: String) : AuthResult()

    /** 服务端要求图形验证码 —— App 无法自动识别，必须请用户去浏览器完成一次登录。 */
    data class NeedCaptcha(val message: String) : AuthResult()

    /** 账号或密码错误。调用方**不得**自动重试（连续错 5 次会锁定账号 / 强制验证码）。 */
    data class BadCredentials(val message: String) : AuthResult()

    /** 其他失败：网络、服务端 5xx、页面结构变化等。[status] 为 0 表示没拿到 HTTP 响应。 */
    data class Failed(val message: String, val status: Int = 0) : AuthResult()
}
