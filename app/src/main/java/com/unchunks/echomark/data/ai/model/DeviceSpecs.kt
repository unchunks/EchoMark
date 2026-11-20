package com.unchunks.echomark.data.ai.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * モデルを選ぶのに使う、この端末の性能。
 * @property totalRamBytes 端末のメモリ。OS が使う分を含まないため、公称より少し小さい
 * @property availableStorageBytes アプリの保存先の空き容量
 * @property socManufacturer SoC のメーカー。分からなければ null
 * @property socModel SoC の型番。分からなければ null
 */
data class DeviceSpecs(
    val totalRamBytes: Long,
    val availableStorageBytes: Long,
    val socManufacturer: String? = null,
    val socModel: String? = null
)

/** この端末の性能を読む。 */
fun interface DeviceSpecsReader {
    fun read(): DeviceSpecs
}

class AndroidDeviceSpecsReader @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceSpecsReader {

    override fun read(): DeviceSpecs {
        val memory = ActivityManager.MemoryInfo().also { requireNotNull(context.getSystemService<ActivityManager>()).getMemoryInfo(it) }
        return DeviceSpecs(
            totalRamBytes = memory.totalMem,
            availableStorageBytes = context.filesDir.usableSpace,
            socManufacturer = Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN },
            socModel = Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }
        )
    }
}
