package com.github.kr328.clash.common.util

import java.io.IOException

class RemoteServiceUnavailableException(cause: Throwable? = null) :
    IOException("Remote service unavailable", cause)
