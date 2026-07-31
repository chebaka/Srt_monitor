package com.chebaka.srtmonitor

class StatusProxy(private val handler: (String) -> Unit) {
    fun onStatus(message: String) = handler(message)
}
