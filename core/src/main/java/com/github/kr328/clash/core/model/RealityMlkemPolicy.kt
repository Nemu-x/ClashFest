package com.github.kr328.clash.core.model

/**
 * How the REALITY ClientHello offers the X25519MLKEM768 key share (core patch 0002).
 * [Auto] learns per server: classic first, flips after a failure, pins after a success.
 * The integer is the value the bridge passes to the core.
 */
enum class RealityMlkemPolicy(val native: Int) {
    Auto(0),
    On(1),
    Off(2),
}
