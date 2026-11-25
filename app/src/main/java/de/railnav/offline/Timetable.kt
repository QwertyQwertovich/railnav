package de.railnav.offline

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact in-memory timetable built from GTFS.
 *
 * Stations are merged stop groups. Trips are stored as runs in the seq* arrays
 * (tripStart[t] until tripStart[t+1]). Connections (one per consecutive stop pair of a trip)
 * are sorted by departure time and reference (trip, position in trip).
 * Times are seconds after midnight of the service day (may exceed 86400).
 */
class Timetable(
    val strings: Array<String>,
    val stationName: IntArray,
    val stationLat: FloatArray,
    val stationLon: FloatArray,
    val fpStart: IntArray,
    val fpTo: IntArray,
    val fpSec: IntArray,
    val tripLine: IntArray,
    val tripHeadsign: IntArray,
    val tripProduct: ByteArray,
    val tripService: IntArray,
    val tripStart: IntArray,
    val seqStation: IntArray,
    val seqArr: IntArray,
    val seqDep: IntArray,
    val seqPlatform: IntArray,
    val connTrip: IntArray,
    val connPos: IntArray,
    val connDep: IntArray,
    val baseEpochDay: Int,
    val numDays: Int,
    val serviceWords: Int,
    val serviceBits: LongArray,
    val timezone: String,
    val feedInfo: String,
) {
    val nStations get() = stationName.size
    val nTrips get() = tripLine.size
    val nConn get() = connTrip.size

    fun name(st: Int) = strings[stationName[st]]

    fun serviceActive(service: Int, dayIdx: Int): Boolean {
        if (dayIdx < 0 || dayIdx >= numDays) return false
        val w = serviceBits[service * serviceWords + (dayIdx ushr 6)]
        return (w ushr (dayIdx and 63)) and 1L == 1L
    }

    /** first connection with dep >= t */
    fun lowerBound(t: Int): Int {
        var lo = 0; var hi = connDep.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (connDep[m] < t) lo = m + 1 else hi = m
        }
        return lo
    }

    fun save(file: File) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { o ->
            o.writeInt(MAGIC)
            o.writeUTF(timezone); o.writeUTF(feedInfo)
            o.writeInt(baseEpochDay); o.writeInt(numDays); o.writeInt(serviceWords)
            o.writeInt(strings.size); strings.forEach { o.writeUTF(it) }
            for (a in listOf(stationName, fpStart, fpTo, fpSec, tripLine, tripHeadsign, tripService, tripStart,
                seqStation, seqArr, seqDep, seqPlatform, connTrip, connPos, connDep)) writeInts(o, a)
            writeFloats(o, stationLat); writeFloats(o, stationLon)
            o.writeInt(tripProduct.size); o.write(tripProduct)
            o.writeInt(serviceBits.size)
            val bb = ByteBuffer.allocate(serviceBits.size * 8).order(ByteOrder.BIG_ENDIAN)
            bb.asLongBuffer().put(serviceBits); o.write(bb.array())
        }
    }

    companion object {
        private const val MAGIC = 0x52414E33 // "RAN3"

        private fun writeInts(o: DataOutputStream, a: IntArray) {
            o.writeInt(a.size)
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.BIG_ENDIAN)
            bb.asIntBuffer().put(a); o.write(bb.array())
        }
        private fun writeFloats(o: DataOutputStream, a: FloatArray) {
            o.writeInt(a.size)
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.BIG_ENDIAN)
            bb.asFloatBuffer().put(a); o.write(bb.array())
        }
        private fun readInts(i: DataInputStream): IntArray {
            val n = i.readInt(); val b = ByteArray(n * 4); i.readFully(b)
            return IntArray(n).also { ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(it) }
        }
        private fun readFloats(i: DataInputStream): FloatArray {
            val n = i.readInt(); val b = ByteArray(n * 4); i.readFully(b)
            return FloatArray(n).also { ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(it) }
        }

        fun load(file: File): Timetable =
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { i ->
                require(i.readInt() == MAGIC) { "Неверный формат офлайн-данных" }
                val tz = i.readUTF(); val info = i.readUTF()
                val base = i.readInt(); val nd = i.readInt(); val sw = i.readInt()
                val strings = Array(i.readInt()) { i.readUTF() }
                val ints = List(15) { readInts(i) }
                val lat = readFloats(i); val lon = readFloats(i)
                val prod = ByteArray(i.readInt()).also { i.readFully(it) }
                val nb = i.readInt(); val b = ByteArray(nb * 8); i.readFully(b)
                val bits = LongArray(nb).also { ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).asLongBuffer().get(it) }
                Timetable(
                    strings, ints[0], lat, lon, ints[1], ints[2], ints[3], ints[4], ints[5], prod, ints[6], ints[7],
                    ints[8], ints[9], ints[10], ints[11], ints[12], ints[13], ints[14], base, nd, sw, bits, tz, info
                )
            }
    }
}
