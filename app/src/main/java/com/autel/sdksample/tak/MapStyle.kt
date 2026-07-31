package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

/**
 * Flight mini-map tile source choice — the osmdroid equivalent of the DJI blueprint's
 * `MaplibreStyle` (which swaps MapLibre style JSON). Same preference file and key names as the
 * DJI side so the two ports stay conceptually parallel.
 *
 * **Street + Custom only, no Hybrid** (operator's call, 2026-07-30). DJI offers a third
 * "Hybrid" satellite+streets option; providing that here would mean picking a satellite tile
 * provider, which is a licensing decision rather than a coding one. A pilot who wants imagery
 * points [CUSTOM] at whatever provider they're entitled to use.
 */
object MapStyle {
    private const val TAG = "MapStyle"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_STYLE = "map_style"          // "street" | "custom"
    private const val KEY_CUSTOM_URL = "map_custom_url"

    const val STREET = "street"
    const val CUSTOM = "custom"

    fun savedStyleChoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, STREET) ?: STREET

    fun savedCustomUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_URL, "") ?: ""

    fun saveStyleChoice(context: Context, choice: String, customUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STYLE, choice)
            .putString(KEY_CUSTOM_URL, customUrl.trim())
            .apply()
        AppLog.i(TAG, "map style saved: $choice" +
            if (choice == CUSTOM) " (${customUrl.trim()})" else "")
    }

    /**
     * The tile source the flight map should use right now.
     *
     * Falls back to street whenever "custom" is selected but the URL is unusable — a blank map
     * in flight because of a typo'd template is a worse failure than quietly showing streets,
     * and the Pre-Flight Setup screen is where a bad URL should be noticed (it validates on
     * save). The fallback is logged so it isn't silent.
     */
    fun tileSource(context: Context): ITileSource {
        if (savedStyleChoice(context) != CUSTOM) return TileSourceFactory.MAPNIK
        val url = savedCustomUrl(context)
        if (!isUsableTemplate(url)) {
            AppLog.w(TAG, "custom tile URL unusable ('$url') — falling back to street tiles")
            return TileSourceFactory.MAPNIK
        }
        return XyzTemplateTileSource(url)
    }

    /** A template is usable if it's an http(s) URL carrying all three XYZ placeholders. */
    fun isUsableTemplate(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        return u.contains("{z}") && u.contains("{x}") && u.contains("{y}")
    }

    /**
     * Tile source for a standard `{z}/{x}/{y}` XYZ template.
     *
     * osmdroid's stock [org.osmdroid.tileprovider.tilesource.XYTileSource] can't be used here:
     * it builds URLs by concatenating `baseUrl + z + "/" + x + "/" + y + ending`, which only
     * works for sources whose path happens to end in that exact order. Pilots paste templates
     * in the usual slippy-map form instead (and providers often put the key or a style segment
     * *after* the tile coordinates), so this substitutes the placeholders wherever they appear.
     */
    private class XyzTemplateTileSource(private val template: String) : OnlineTileSourceBase(
        "custom-xyz",
        MIN_ZOOM,
        MAX_ZOOM,
        TILE_SIZE_PX,
        "",              // filename ending is part of the template, not appended
        arrayOf(template),
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            template
                .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
                .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
                .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
    }

    private const val MIN_ZOOM = 0
    private const val MAX_ZOOM = 22
    /** 256 px — the near-universal slippy-map tile size. A provider serving 512 px retina tiles
     *  would render at the wrong scale; that's a per-provider setting worth exposing only if a
     *  pilot actually hits it. */
    private const val TILE_SIZE_PX = 256
}
