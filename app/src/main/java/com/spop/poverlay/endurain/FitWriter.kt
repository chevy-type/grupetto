package com.spop.poverlay.endurain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal FIT activity file writer for indoor cycling.
 * No external SDK needed — implements only what Endurain requires.
 */
object FitWriter {

    /** Seconds between Unix epoch (1970) and FIT epoch (1989-01-01 00:00:00 UTC) */
    private const val FIT_EPOCH_OFFSET = 631065600L

    data class SensorRecord(
        val timestampUnixSec: Long,
        val powerWatts: Int,
        val cadenceRpm: Int,
        val speedMps: Float,   // meters per second
        val heartRate: Int     // 0 = unavailable
    )

    fun buildFitFile(records: List<SensorRecord>): ByteArray {
        if (records.isEmpty()) return ByteArray(0)

        val startUnix  = records.first().timestampUnixSec
        val endUnix    = records.last().timestampUnixSec
        val startFit   = (startUnix - FIT_EPOCH_OFFSET).toInt()
        val endFit     = (endUnix   - FIT_EPOCH_OFFSET).toInt()
        val elapsedMs  = ((endUnix  - startUnix) * 1000L).toInt()

        // Summary values
        val avgPower       = records.map { it.powerWatts  }.average().toInt()
        val maxPower       = records.maxOf { it.powerWatts }
        val avgCadence     = records.map { it.cadenceRpm  }.average().toInt()
        val maxCadence     = records.maxOf { it.cadenceRpm }
        val avgSpeedMms    = (records.map { it.speedMps }.average() * 1000).toInt()
        val totalDistCm    = (records.sumOf { it.speedMps.toDouble() } * 100).toInt()
        val totalCalories  = (records.sumOf { it.powerWatts.toDouble() } / 4184.0 / 0.22).toInt()

        val data = ByteArrayOutputStream()

        // file_id  (global 0)
        data += defMsg(0, 0, listOf(
            F(8, 1, 0x00),  // type: enum
            F(1, 4, 0x86),  // time_created: uint32
            F(2, 2, 0x84),  // manufacturer: uint16
        ))
        data += dataMsg(0, b(4,1), b(startFit,4), b(255,2))

        // event: start  (global 21)
        data += defMsg(1, 21, listOf(F(253,4,0x86), F(0,1,0x00), F(1,1,0x00)))
        data += dataMsg(1, b(startFit,4), b(0,1), b(0,1))  // timer / start

        // record  (global 20)
        data += defMsg(2, 20, listOf(
            F(253,4,0x86),  // timestamp
            F(7,  2,0x84),  // power (W)
            F(4,  1,0x02),  // cadence (rpm)
            F(6,  2,0x84),  // speed (mm/s)
            F(3,  1,0x02),  // heart_rate (bpm)
        ))
        for (r in records) {
            val ts      = (r.timestampUnixSec - FIT_EPOCH_OFFSET).toInt()
            val speedMm = (r.speedMps * 1000f).toInt().coerceIn(0, 65535)
            data += dataMsg(2,
                b(ts,4),
                b(r.powerWatts.coerceIn(0,65535), 2),
                b(r.cadenceRpm.coerceIn(0,255),   1),
                b(speedMm, 2),
                b(r.heartRate.coerceIn(0,255),    1),
            )
        }

        // lap  (global 19)
        data += defMsg(3, 19, listOf(
            F(253,4,0x86), F(2,4,0x86), F(7,4,0x86),  F(8,4,0x86),
            F(9,4,0x86),   F(15,2,0x84),F(16,2,0x84), F(13,2,0x84),
            F(53,1,0x02),  F(54,1,0x02),F(0,1,0x00),  F(1,1,0x00),
            F(24,1,0x00),  F(25,1,0x00),
        ))
        data += dataMsg(3,
            b(endFit,4), b(startFit,4), b(elapsedMs,4), b(elapsedMs,4),
            b(totalDistCm,4),
            b(avgPower.coerceIn(0,65535),2),  b(maxPower.coerceIn(0,65535),2),
            b(avgSpeedMms.coerceIn(0,65535),2),
            b(avgCadence.coerceIn(0,255),1),  b(maxCadence.coerceIn(0,255),1),
            b(9,1), b(1,1),  // event: lap / stop
            b(2,1), b(6,1),  // sport: cycling / indoor_cycling
        )

        // session  (global 18)
        data += defMsg(4, 18, listOf(
            F(253,4,0x86), F(2,4,0x86),  F(7,4,0x86),   F(8,4,0x86),
            F(9,4,0x86),   F(11,2,0x84), F(14,2,0x84),  F(15,2,0x84),
            F(19,2,0x84),  F(20,1,0x02), F(21,1,0x02),  F(5,2,0x84),
            F(0,1,0x00),   F(1,1,0x00),  F(24,1,0x00),  F(25,1,0x00),
        ))
        data += dataMsg(4,
            b(endFit,4), b(startFit,4), b(elapsedMs,4), b(elapsedMs,4),
            b(totalDistCm,4),
            b(avgSpeedMms.coerceIn(0,65535),2),
            b(avgPower.coerceIn(0,65535),2),  b(maxPower.coerceIn(0,65535),2),
            b(totalCalories.coerceIn(0,65535),2),
            b(avgCadence.coerceIn(0,255),1),  b(maxCadence.coerceIn(0,255),1),
            b(1,2),           // num_laps
            b(8,1), b(1,1),   // event: session / stop
            b(2,1), b(6,1),   // cycling / indoor
        )

        // activity  (global 34)
        data += defMsg(5, 34, listOf(
            F(253,4,0x86), F(0,4,0x86), F(1,2,0x84),
            F(2,1,0x00),   F(3,1,0x00), F(4,1,0x00),
        ))
        data += dataMsg(5,
            b(endFit,4), b(elapsedMs,4), b(1,2),
            b(0,1), b(26,1), b(1,1),  // manual / activity / stop
        )

        // Assemble with 14-byte header + CRCs
        val dataBytes = data.toByteArray()
        val file = ByteArrayOutputStream()
        file.write(14); file.write(0x20)
        file += b(2132, 2)
        file += b(dataBytes.size, 4)
        file.write(".FIT".toByteArray(Charsets.US_ASCII))
        file += b(crc16(file.toByteArray()), 2)
        file.write(dataBytes)
        val all = file.toByteArray()
        file += b(crc16(all), 2)
        return file.toByteArray()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private data class F(val num: Int, val size: Int, val baseType: Int)

    private fun defMsg(local: Int, global: Int, fields: List<F>): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(0x40 or local); buf.write(0); buf.write(0)
        buf += b(global, 2)
        buf.write(fields.size)
        fields.forEach { buf.write(it.num); buf.write(it.size); buf.write(it.baseType) }
        return buf.toByteArray()
    }

    private fun dataMsg(local: Int, vararg values: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(local)
        values.forEach { buf.write(it) }
        return buf.toByteArray()
    }

    private fun b(value: Int, size: Int): ByteArray {
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        when (size) { 1 -> buf.put(value.toByte()); 2 -> buf.putShort(value.toShort()); 4 -> buf.putInt(value) }
        return buf.array()
    }

    private operator fun ByteArrayOutputStream.plusAssign(bytes: ByteArray) { write(bytes) }

    // FIT CRC-16
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val byte = b.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]; crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]; crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }
}
