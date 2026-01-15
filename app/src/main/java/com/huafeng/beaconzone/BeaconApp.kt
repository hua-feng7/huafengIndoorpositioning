package com.huafeng.beaconzone

import android.app.Application
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser

class BeaconApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val beaconManager = BeaconManager.getInstanceForApplication(this)

        // 清空默认解析器
        beaconManager.beaconParsers.clear()

        // iBeacon 标准格式
        beaconManager.beaconParsers.add(
            BeaconParser().setBeaconLayout(
                "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"
            )
        )

        // 你信标 500ms 广播，这里 1s 扫描一轮
        beaconManager.foregroundScanPeriod = 1000L
        beaconManager.foregroundBetweenScanPeriod = 0L
    }
}
