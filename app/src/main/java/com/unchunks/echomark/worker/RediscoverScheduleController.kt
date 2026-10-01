package com.unchunks.echomark.worker

import androidx.work.WorkManager
import com.unchunks.echomark.domain.repository.RediscoverSettings
import javax.inject.Inject

/**
 * 再発見通知の設定を WorkManager の予定に反映する。
 * 設定画面とオンボーディングから使い、ViewModel のテストでは Fake に差し替える。
 */
interface RediscoverScheduleController {
    /** オンなら設定の曜日・時刻で登録し直し、オフなら取り消す。 */
    fun apply(settings: RediscoverSettings)
}

class WorkManagerRediscoverScheduleController @Inject constructor(
    private val workManager: WorkManager
) : RediscoverScheduleController {

    override fun apply(settings: RediscoverSettings) {
        if (settings.enabled) {
            RediscoverDigestScheduler.schedule(workManager, settings.dayOfWeek, settings.hour, settings.minute)
        } else {
            RediscoverDigestScheduler.cancel(workManager)
        }
    }
}
