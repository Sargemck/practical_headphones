package io.github.sargemck.practicalheadphones.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.sargemck.practicalheadphones.AppGraph
import io.github.sargemck.practicalheadphones.state.HeadphoneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows the current mode, a tap goes to the next starred mode. Talks to the
 * running [io.github.sargemck.practicalheadphones.service.HeadphoneService] through [AppGraph];
 * unavailable when it isn't running or the headphones aren't connected. Long-press opens the app
 * (QS_TILE_PREFERENCES on MainActivity).
 */
class ModeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var model = TileModel.from(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = scope.launch {
            AppGraph.controller
                .flatMapLatest { controller -> controller?.state?.map { TileModel.from(it) } ?: flowOf(TileModel.from(null)) }
                .distinctUntilChanged()
                .collect(::show)
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (model.next == null) return
        controller()?.nextFavouriteMode()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        setAdded(this, true)
    }

    override fun onTileRemoved() {
        setAdded(this, false)
        super.onTileRemoved()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun controller(): HeadphoneController? = AppGraph.controller.value

    private fun show(next: TileModel) {
        model = next
        val tile = qsTile ?: return
        tile.state = when (next.status) {
            TileModel.Status.Active -> Tile.STATE_ACTIVE
            TileModel.Status.Inactive -> Tile.STATE_INACTIVE
            TileModel.Status.Unavailable -> Tile.STATE_UNAVAILABLE
        }
        tile.label = next.label
        tile.subtitle = next.subtitle
        tile.updateTile()
    }

    companion object {
        private const val PREFS = "tile"
        private const val ADDED = "added"

        /** Whether the tile is in Quick Settings, as far as this app was told. */
        fun isAdded(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ADDED, false)

        fun setAdded(context: Context, added: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ADDED, added).apply()
        }
    }
}
