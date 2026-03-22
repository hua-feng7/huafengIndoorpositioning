package com.huafeng.beaconzone

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FingerprintEntity::class,
        MapEntity::class // ✅ 必须在这里注册新表
    ],
    version = 2, // ✅ 数据库结构变了，版本号从 1 改为 2
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {

    abstract fun fingerprintDao(): FingerprintDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null

        fun get(ctx: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDb::class.java,
                    "beaconzone.db"
                )
                .fallbackToDestructiveMigration() // ✅ 测试阶段：版本升级时直接清空旧数据，避免编写迁移脚本
                .build().also { INSTANCE = it }
            }
    }
}
