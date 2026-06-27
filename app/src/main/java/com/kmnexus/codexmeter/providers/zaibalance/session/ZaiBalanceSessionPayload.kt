package com.kmnexus.codexmeter.providers.zaibalance.session

import kotlinx.serialization.Serializable

/** The z.ai (bigmodel) API key used to read the account balance via `Authorization: Bearer`. */
@Serializable
data class ZaiBalanceSessionPayload(val apiKey: String)
