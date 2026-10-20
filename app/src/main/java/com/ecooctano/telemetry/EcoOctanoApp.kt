package com.ecooctano.telemetry

import android.app.Application
import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File

class EcoOctanoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
    }

    /**
     * OSMDroid exige um User-Agent identificável (política de uso dos tiles do OSM).
     * O cache de tiles fica no armazenamento interno do app — nenhuma permissão de
     * armazenamento é necessária e as tiles da pista ficam disponíveis offline depois
     * de visualizadas uma vez.
     */
    private fun configureOsmdroid() {
        try {
            val config = Configuration.getInstance()
            config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            config.userAgentValue = BuildConfig.APPLICATION_ID
            val base = File(filesDir, "osmdroid").apply { mkdirs() }
            config.osmdroidBasePath = base
            config.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            config.tileFileSystemCacheMaxBytes = 500L * 1024L * 1024L
            config.tileFileSystemCacheTrimBytes = 400L * 1024L * 1024L
        } catch (t: Throwable) {
            Log.e("EcoOctanoApp", "Falha ao configurar OSMDroid", t)
        }
    }
}
