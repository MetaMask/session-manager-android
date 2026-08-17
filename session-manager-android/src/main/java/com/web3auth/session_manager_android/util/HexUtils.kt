package com.web3auth.session_manager_android.util

import java.math.BigInteger

object HexUtils {
    private val HEX_REGEX = Regex("^(0x|0X)?[0-9a-fA-F]+$")

    fun remove0x(hex: String): String {
        return if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex.substring(2)
        } else {
            hex
        }
    }

    fun add0x(hex: String): String {
        return if (hex.startsWith("0x") || hex.startsWith("0X")) {
            "0x" + hex.substring(2)
        } else {
            "0x$hex"
        }
    }

    fun isHexString(value: String): Boolean {
        return value.isNotEmpty() && HEX_REGEX.matches(value)
    }

    /**
     * Pads a hex session id to 32 bytes (64 hex chars), matching web `padHexString`.
     */
    fun padHexString(hexString: String): String {
        return remove0x(hexString).padStart(64, '0').take(64)
    }

    fun toBigInteger(hex: String): BigInteger {
        return BigInteger(remove0x(hex), 16)
    }
}
