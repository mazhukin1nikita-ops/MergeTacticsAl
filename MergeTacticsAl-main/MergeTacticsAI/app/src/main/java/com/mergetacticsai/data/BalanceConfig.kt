package com.mergetacticsai.data

import org.json.JSONObject

data class BalanceConfig(val version: String, val verified: Boolean) {
    companion object {
        fun parse(json: String): BalanceConfig {
            val v = JSONObject(json).optString("version", "unverified")
            return BalanceConfig(v, v != "unverified")
        }
    }
}
