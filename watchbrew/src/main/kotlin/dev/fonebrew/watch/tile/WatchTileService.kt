package dev.fonebrew.watch.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.fonebrew.watch.MainActivity
import dev.fonebrew.watch.core.Glyph
import dev.fonebrew.watch.core.TileContent
import dev.fonebrew.watch.core.TilePresenter
import dev.fonebrew.watch.core.WatchPalette
import dev.fonebrew.watch.watchRuntime
import dev.aarso.watch.R

/**
 * The one Tile: an **Ask** shortcut (tapping anywhere opens the Ask screen) and a snippet of the last
 * finished reply, with who answered — the provenance glyph and words ("on-device" / "cloud · watched")
 * reach the wrist's Tile exactly as they do the Ask screen — and how old that reply is.
 *
 * The content is derived by the pure [TilePresenter]; this class only lays it out. The Tile cannot
 * know the live link state from a cold process (the connection is never claimed from a cache), so it
 * shows the last reply *with its age*, and a link note only when this process has seen the link fail.
 *
 * ProtoLayout rendering, tint, and how the vector provenance glyphs look in the Tile carousel are
 * device-verified only (watchbrew/OWNER_VERIFY.md).
 */
internal class WatchTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val state = watchRuntime.controller.state.value
        val content = TilePresenter.present(state.lastReply, state.connection, System.currentTimeMillis())
        val layout = TileLayout.build(this, content)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(TileLayout.RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(TileLayout.resources())

    private fun <T : Any> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "WatchTile"
        }
}

internal object TileLayout {
    const val RESOURCES_VERSION = "1"
    private const val IMG_BRAND = "brand"
    private const val IMG_CLOUD = "cloud_watched"
    private const val IMG_HOUSE = "on_device"

    fun resources(): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(IMG_BRAND, image(R.drawable.ic_stat_fonebrew))
            .addIdToImageMapping(IMG_CLOUD, image(R.drawable.glyph_cloud_watched))
            .addIdToImageMapping(IMG_HOUSE, image(R.drawable.glyph_on_device))
            .build()

    private fun image(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build())
            .build()

    fun build(context: Context, content: TileContent): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open_ask")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(MainActivity.EXTRA_DESTINATION, ActionBuilders.stringExtra(MainActivity.DEST_ASK))
                            .build(),
                    )
                    .build(),
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(headerRow())
            .addContent(spacer(6f))
        content.snippet?.let { column.addContent(text(it, 13f, WatchPalette.PAPER, maxLines = 3)) }
            ?: column.addContent(text(TilePresenter.NO_REPLY, 13f, WatchPalette.PAPER_MID, maxLines = 1))
        content.provenance?.let { p ->
            column.addContent(spacer(4f))
            column.addContent(provenanceRow(p.glyph, p.label))
        }
        content.age?.let { column.addContent(text(it, 11f, WatchPalette.PAPER_MID, maxLines = 1)) }
        content.linkNote?.let { column.addContent(text(it, 11f, WatchPalette.PAPER, maxLines = 1)) }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(DimensionBuilders.dp(18f)).build())
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(WatchPalette.INK)).build())
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder().setContentDescription(TypeBuilders.StringProp.Builder(content.contentDescription).build()).build(),
                    )
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    private fun headerRow(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(icon(IMG_BRAND, 18f, WatchPalette.PAPER))
            .addContent(spacer(6f, horizontal = true))
            .addContent(text("Ask", 15f, WatchPalette.PAPER, bold = true, maxLines = 1))
            .build()

    private fun provenanceRow(glyph: Glyph, label: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(icon(if (glyph == Glyph.CLOUD_WATCHED) IMG_CLOUD else IMG_HOUSE, 14f, WatchPalette.PAPER))
            .addContent(spacer(4f, horizontal = true))
            .addContent(text(label, 11f, WatchPalette.PAPER, maxLines = 1))
            .build()

    private fun icon(resourceId: String, size: Float, tint: Long): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(resourceId)
            .setWidth(DimensionBuilders.dp(size))
            .setHeight(DimensionBuilders.dp(size))
            .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(tint)).build())
            .build()

    private fun text(value: String, sp: Float, color: Long, bold: Boolean = false, maxLines: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(sp))
                    .setColor(argb(color))
                    .apply { if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD) }
                    .build(),
            )
            .build()

    private fun spacer(size: Float, horizontal: Boolean = false): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().apply {
            if (horizontal) setWidth(DimensionBuilders.dp(size)) else setHeight(DimensionBuilders.dp(size))
        }.build()

    private fun argb(color: Long): ColorBuilders.ColorProp = ColorBuilders.ColorProp.Builder(WatchPalette.argbInt(color)).build()
}
