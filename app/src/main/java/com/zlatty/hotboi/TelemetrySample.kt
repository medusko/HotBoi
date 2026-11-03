package com.zlatty.hotboi

import com.google.gson.annotations.SerializedName

data class TelemetrySample(
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("optic_temp") val opticTemp: Double,
    @SerializedName("cpu0_temp") val cpu0Temp: Double,
    @SerializedName("cpu1_temp") val cpu1Temp: Double,
    @SerializedName("ploam") val ploam: String,
    @SerializedName("rx_power") val rxPower: Double,
    @SerializedName("tx_power") val txPower: Double,
    @SerializedName("bias_ma") val biasMa: Double,
    @SerializedName("ping_ms") val pingMs: Double
)
