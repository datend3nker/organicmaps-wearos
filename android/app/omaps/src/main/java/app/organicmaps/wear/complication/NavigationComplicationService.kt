package app.organicmaps.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.organicmaps.wear.NavigationStateHolder

class NavigationComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("150m").build(),
                contentDescription = PlainComplicationText.Builder("Distance to Turn").build()
            ).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("150m to Avenue de l'Opéra").build(),
                contentDescription = PlainComplicationText.Builder("Navigation Info").build()
            ).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val state = NavigationStateHolder.state.value
        if (!state.isActive || state.distToTurn.isEmpty()) return null

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(state.distToTurn).build(),
                contentDescription = PlainComplicationText.Builder("Distance: ${state.distToTurn}").build()
            ).build()
            ComplicationType.LONG_TEXT -> {
                val text = if (state.nextStreet.isNotEmpty()) "${state.distToTurn} to ${state.nextStreet}" else state.distToTurn
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text).build(),
                    contentDescription = PlainComplicationText.Builder("Navigation: $text").build()
                ).build()
            }
            else -> null
        }
    }
}
