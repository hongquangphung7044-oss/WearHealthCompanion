package com.wearhealth.companion.mobile.data

import android.content.Context
import androidx.room.Room

object EcgStore {
    @Volatile private var instance: EcgDatabase? = null

    fun db(context: Context): EcgDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, EcgDatabase::class.java, "ecg.db")
            .build()
            .also { instance = it }
    }

    fun rawFile(context: Context, recordId: String) =
        context.getDir("ecg_raw", Context.MODE_PRIVATE).resolve("$recordId.ecgz")
}
