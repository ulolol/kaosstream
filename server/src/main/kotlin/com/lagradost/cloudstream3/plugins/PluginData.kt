package com.lagradost.cloudstream3.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class PluginData(
    @SerialName("internalName") val internalName: String,
    @SerialName("url") val url: String?,
    @SerialName("isOnline") val isOnline: Boolean,
    @SerialName("filePath") val filePath: String,
    @SerialName("version") val version: Int
)
