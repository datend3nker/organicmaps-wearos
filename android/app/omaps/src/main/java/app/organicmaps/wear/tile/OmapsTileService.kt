package app.organicmaps.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.presentation.Omaps
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Typography
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class OmapsTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val lastDest = NavigationStateHolder.state.value.destinationName
        
        return Futures.immediateFuture(TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(
                LayoutElementBuilders.Box.Builder()
                    .addContent(
                        LayoutElementBuilders.Column.Builder()
                            .addContent(
                                Text.Builder(this, "Organic Maps")
                                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                                    .setColor(ColorBuilders.argb(0xFF4CAF50.toInt()))
                                    .build()
                            )
                            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build())
                            .addContent(
                                Text.Builder(this, if (lastDest.isNotEmpty()) "Last: $lastDest" else "Where to?")
                                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                                    .setMaxLines(2)
                                    .build()
                            )
                            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(12f)).build())
                            .addContent(
                                Button.Builder(this, ModifiersBuilders.Clickable.Builder()
                                    .setOnClick(ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(Omaps::class.java.name)
                                            .build())
                                        .build())
                                    .build())
                                    .setTextContent("Search")
                                    .setButtonColors(ButtonDefaults.PRIMARY_COLORS)
                                    .build()
                            )
                            .build()
                    )
                    .setModifiers(ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(DimensionBuilders.dp(12f)).build())
                        .build())
                    .build()
            ))
            .build())
    }
}
