package com.carvo.app;

import android.content.Intent;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: pull the shade, tap, run.
 * The system owns the tile — zero battery, zero background work.
 */
public class RunTile extends TileService {

    @Override
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("tile_start", true);
        // targetSdk 33: the Intent variant works on every Android version we support
        startActivityAndCollapse(i);
    }
}
