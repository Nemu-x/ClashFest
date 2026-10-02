package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer.State
import kotlinx.coroutines.channels.Channel

class SpeedWidgetModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }
        try {
            renderCurrent()
            for (event in profileLoaded) renderCurrent()
        } finally {
            SpeedWidgetRenderer.renderAll(service, State.Off)
        }
    }

    private fun renderCurrent() {
        SpeedWidgetRenderer.renderAll(
            service,
            State.fromStatus(StatusProvider.serviceRunning, StatusProvider.currentProfile != null),
        )
    }
}
