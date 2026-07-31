package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Offline map tiles: the cache osmdroid writes as you fly, and the pre-flight region download.
 *
 * **Two separate mechanisms, and it matters which one a pilot is relying on.**
 *  - *Automatic caching* keeps every tile the flight map draws. It costs nothing extra — the
 *    tile was fetched to be shown anyway — so a route flown once with a connection is on the
 *    controller for the next flight. It only ever covers ground the map has actually displayed.
 *  - *Region download* fetches a whole area in advance, including ground the aircraft has not
 *    been over yet. This is the one that makes a first flight into new terrain work with no
 *    signal, and it is deliberately a manual pre-flight action for the same reason the UASFM
 *    download is: nothing in flight should ever depend on the network.
 *
 * Both write to the same store, so a region download and normal use share the [MAX_BYTES]
 * budget and the same eviction.
 */
object MapTileCache {
    private const val TAG = "MapTileCache"

    /** Hard ceiling on the tile store (operator's spec, 2026-07-30). */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * What osmdroid trims down to when [MAX_BYTES] is passed, oldest first.
     *
     * Deliberately below the ceiling rather than equal to it. If trim == max, the store sits
     * exactly at the limit and re-trims on almost every new tile, which turns a background
     * cleanup into a constant one. The 10% gap means a trim reclaims real space and then
     * leaves the cache alone for a while.
     */
    private const val TRIM_BYTES = (MAX_BYTES * 0.9).toLong()

    /**
     * How long a cached tile stays usable, regardless of what the server's headers said.
     *
     * Without this the cache does not survive the thing it exists for. OSM tile servers send
     * short expiry times, osmdroid honours them, and its cleanup deletes expired tiles — so a
     * cache filled at home would be treated as stale by the time it mattered, and a map with
     * no signal would show blank squares while holding the tiles that would have filled them.
     *
     * The cost is that road changes take up to a year to appear. For a map whose job is to
     * orient a pilot, out-of-date is enormously better than absent.
     */
    private val EXPIRY_OVERRIDE_MS = 365L * 24 * 60 * 60 * 1000

    /** Rough bytes per 256px street tile, from OSM's own published averages. Used only to
     *  show a pilot a size before a download — never to make a decision in code. */
    private const val BYTES_PER_TILE_EST = 15L * 1024

    @Volatile private var configured = false

    /**
     * Sets up osmdroid's storage. Must run before any MapView inflates AND before any cache
     * read, so both the flight screen and Pre-Flight Setup call it. Idempotent.
     *
     * The base path is app-private ([Context.getFilesDir]) rather than shared external storage:
     * on Android 11 the Smart Controller runs, osmdroid's default external path needs storage
     * permission this app has no other reason to ask for, and a cache that silently fails to
     * write is worse than one in a less convenient place.
     */
    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        val base = File(context.filesDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            tileFileSystemCacheMaxBytes = MAX_BYTES
            tileFileSystemCacheTrimBytes = TRIM_BYTES
            expirationOverrideDuration = EXPIRY_OVERRIDE_MS
        }
        configured = true
        AppLog.i(TAG, "osmdroid cache configured: max=${human(MAX_BYTES)} " +
            "trim=${human(TRIM_BYTES)} path=${base.absolutePath}")
    }

    /** Bytes currently held. Reads the sqlite file directly — [SqlTileWriter.getSize] needs a
     *  live writer instance, and this is called from a settings screen that has no map. */
    fun usedBytes(context: Context): Long {
        configure(context)
        val db = File(Configuration.getInstance().osmdroidTileCache, SqlTileWriter.DATABASE_FILENAME)
        return if (db.exists()) db.length() else 0L
    }

    /** Deletes every cached tile. */
    fun clear(context: Context): Boolean {
        configure(context)
        return try {
            val ok = SqlTileWriter().purgeCache()
            AppLog.i(TAG, "cache purge: $ok")
            ok
        } catch (t: Throwable) {
            AppLog.w(TAG, "cache purge failed: ${t.message}")
            false
        }
    }

    /**
     * Whether [source] permits bulk download without an override.
     *
     * False for the Street map: osmdroid marks OSM's Mapnik source FLAG_NO_BULK because OSM's
     * tile usage policy prohibits bulk downloading from their donated public servers, and its
     * CacheManager constructor throws rather than let an app do it. Custom sources carry no
     * such flag.
     *
     * The app can still download street tiles — see [bulkSourceFor] — but the caller is
     * expected to confirm with the pilot first, which is why this stays a separate question
     * from "is a download possible".
     */
    fun allowsBulkDownload(source: ITileSource): Boolean =
        source is OnlineTileSourceBase && source.tileSourcePolicy.acceptsBulkDownload()

    /**
     * A bulk-capable equivalent of [source], or the source itself when it already allows it.
     *
     * For the street map this returns a clone of osmdroid's Mapnik source with FLAG_NO_BULK
     * cleared (operator's decision, 2026-07-30: this is a public-safety aircraft and an offline
     * map is a life-safety item). Everything else about the policy is kept deliberately:
     *  - **Max 2 concurrent connections**, exactly as stock Mapnik. Raising it is what turns a
     *    tolerated download into an abusive one, and it would not make the job meaningfully
     *    faster.
     *  - **User-agent flags kept**, so the download identifies this app rather than pretending
     *    to be something else. [configure] sets the agent to the package name.
     *
     * **The name must stay "Mapnik".** Cached tiles are keyed by `ITileSource.name()` in the
     * `provider` column, so a differently-named clone would download into rows the flight map
     * never reads — a download that appears to work and changes nothing.
     */
    fun bulkSourceFor(source: ITileSource): ITileSource {
        if (allowsBulkDownload(source)) return source
        if (source.name() != TileSourceFactory.MAPNIK.name()) return source
        return XYTileSource(
            TileSourceFactory.MAPNIK.name(),
            0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/",
                "https://c.tile.openstreetmap.org/",
            ),
            "© OpenStreetMap contributors",
            TileSourcePolicy(
                2,
                TileSourcePolicy.FLAG_NO_PREVENTIVE
                    or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
                    or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
            ),
        )
    }

    /** Square bounding box of [radiusMi] around a point. Same math as [UasfmStore.bboxAround],
     *  so the two downloads cover the same ground for the same numbers. */
    fun bboxAround(lat: Double, lon: Double, radiusMi: Double): BoundingBox {
        val milesPerDegLat = 69.0
        val dLat = radiusMi / milesPerDegLat
        val cosLat = max(0.01, cos(Math.toRadians(lat)))
        val dLon = radiusMi / (milesPerDegLat * cosLat)
        return BoundingBox(
            min(90.0, lat + dLat), min(180.0, lon + dLon),
            max(-90.0, lat - dLat), max(-180.0, lon - dLon),
        )
    }

    /** Tiles a region download would fetch, and roughly what they weigh. */
    fun estimate(bbox: BoundingBox): Pair<Int, Long> {
        val tiles = CacheManager.getTilesCoverage(bbox, ZOOM, ZOOM).size
        return tiles to (tiles * BYTES_PER_TILE_EST)
    }

    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    /**
     * Progress and outcome of a region download, on the main thread.
     *
     * Only the completed count is reported. The caller already knows the total from
     * [estimate] — and osmdroid's own callback does NOT supply one, which is easy to get
     * wrong (see [downloadRegion]).
     */
    interface Progress {
        fun onProgress(done: Int)
        fun onDone(downloaded: Int)
        fun onFailed(reason: String)
    }

    /**
     * Downloads every tile in [bbox] at the flight map's zoom.
     *
     * Only [ZOOM] is fetched, not a range. The mini-map is hard-locked to that zoom, so any
     * other level would be bytes that can never be drawn — and at zoom 16 the tile count grows
     * with the square of the radius, so there is no spare budget to waste.
     */
    fun downloadRegion(
        context: Context,
        source: ITileSource,
        bbox: BoundingBox,
        progress: Progress,
    ) {
        configure(context)
        val effective = bulkSourceFor(source)
        val manager = try {
            CacheManager(effective, SqlTileWriter(), ZOOM, ZOOM)
        } catch (t: Throwable) {
            AppLog.w(TAG, "CacheManager rejected source ${effective.name()}: ${t.message}")
            progress.onFailed("This map source does not allow area downloads")
            return
        }
        AppLog.i(TAG, "region download start: $bbox zoom=$ZOOM source=${effective.name()}" +
            if (effective !== source) " (bulk override)" else "")
        manager.downloadAreaAsyncNoUI(context, bbox, ZOOM, ZOOM,
            object : CacheManager.CacheManagerCallback {
                private var lastDone = 0
                override fun setPossibleTilesInArea(count: Int) {}
                override fun downloadStarted() {}
                /**
                 * osmdroid's signature is
                 * `updateProgress(progress, currentZoomLevel, zoomMin, zoomMax)` — verified in
                 * the 6.1.14 bytecode. **None of the last three is a tile total.** Reading the
                 * fourth as one produced "Downloading 2460 of 16", because 16 was the zoom
                 * level. Only the first parameter is used here; the total belongs to the caller.
                 */
                override fun updateProgress(done: Int, curZoom: Int, zoomMin: Int, zoomMax: Int) {
                    lastDone = done
                    progress.onProgress(done)
                }
                override fun onTaskComplete() {
                    AppLog.i(TAG, "region download complete: $lastDone tiles")
                    progress.onDone(lastDone)
                }
                override fun onTaskFailed(errors: Int) {
                    // Partial results are kept on purpose: the tiles that did arrive are still
                    // usable offline, and deleting them because the tail of the job failed
                    // would throw away exactly what the pilot asked for.
                    AppLog.w(TAG, "region download failed with $errors errors")
                    progress.onFailed("$errors tiles failed. The rest are saved.")
                }
            })
    }

    /** The flight mini-map's locked zoom. Kept in step with FlightActivity.MAP_ZOOM. */
    private const val ZOOM = 16
}
