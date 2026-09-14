package com.github.kr328.clash.service.util

/**
 * A subscription fetch failure that [FetchErrorClassifier] could explain. [message] is the
 * human-readable English reason ending in the `[E-xx]` catalogue code (docs/errors.md); [code]
 * is that code on its own so notifications can pick a localized title/body and the right
 * action, and the optional details carry what the body needs to say (expiry date, HTTP status)
 * and where the "Renew" / "Support" action should lead.
 */
class SubscriptionFetchException(
    message: String,
    cause: Throwable?,
    val code: String?,
    val supportUrl: String? = null,
    val expireAtSeconds: Long? = null,
    val httpStatus: Int? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val CODE = Regex("""\[(E-\d+)]""")

        fun codeOf(message: String?): String? =
            message?.let { CODE.find(it)?.groupValues?.get(1) }

        fun of(
            message: String,
            cause: Throwable?,
            supportUrl: String? = null,
            expireAtSeconds: Long? = null,
            httpStatus: Int? = null,
        ) = SubscriptionFetchException(message, cause, codeOf(message), supportUrl, expireAtSeconds, httpStatus)
    }
}
