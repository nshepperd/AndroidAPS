package app.aaps.plugins.main.general.bgMeter

import java.time.LocalDateTime
import java.util.UUID

/**
 * Bluetooth SIG Glucose Profile (GLP 1.0 / GLS 1.0) definitions and parsers.
 *
 * Pure byte-level code, no Android dependencies, so it can be unit tested.
 */
object GlucoseProfile {

    private fun uuid16(short: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val GLUCOSE_SERVICE: UUID = uuid16(0x1808)
    val CURRENT_TIME_SERVICE: UUID = uuid16(0x1805)
    val DEVICE_INFO_SERVICE: UUID = uuid16(0x180A)

    val GLUCOSE_MEASUREMENT: UUID = uuid16(0x2A18)
    val GLUCOSE_MEASUREMENT_CONTEXT: UUID = uuid16(0x2A34)
    val RECORD_ACCESS_CONTROL_POINT: UUID = uuid16(0x2A52)

    /** Date Time (Roche meters expose this inside the glucose service) */
    val DATE_TIME: UUID = uuid16(0x2A08)

    /** Current Time (inside the Current Time Service) */
    val CURRENT_TIME: UUID = uuid16(0x2A2B)
    val MANUFACTURER_NAME: UUID = uuid16(0x2A29)
    val MODEL_NUMBER: UUID = uuid16(0x2A24)

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid16(0x2902)

    // RACP op codes
    const val RACP_REPORT_STORED_RECORDS = 0x01
    const val RACP_NUMBER_OF_RECORDS_RESPONSE = 0x05
    const val RACP_RESPONSE_CODE = 0x06

    // RACP operators / filter types
    private const val RACP_OPERATOR_ALL = 0x01
    private const val RACP_OPERATOR_GREATER_OR_EQUAL = 0x03
    private const val RACP_FILTER_SEQUENCE_NUMBER = 0x01

    // RACP response codes
    const val RACP_SUCCESS = 0x01
    const val RACP_NO_RECORDS_FOUND = 0x06

    private const val SAMPLE_TYPE_CONTROL_SOLUTION = 0x0A
    private const val SAMPLE_LOCATION_CONTROL_SOLUTION = 0x04

    /** mg/dL per mmol/L for glucose */
    private const val MMOLL_TO_MGDL = 18.0182

    fun racpReportAllRecords(): ByteArray = byteArrayOf(RACP_REPORT_STORED_RECORDS.toByte(), RACP_OPERATOR_ALL.toByte())

    fun racpReportRecordsFrom(sequence: Int): ByteArray = byteArrayOf(
        RACP_REPORT_STORED_RECORDS.toByte(),
        RACP_OPERATOR_GREATER_OR_EQUAL.toByte(),
        RACP_FILTER_SEQUENCE_NUMBER.toByte(),
        (sequence and 0xFF).toByte(),
        ((sequence shr 8) and 0xFF).toByte()
    )

    sealed class RacpResponse {
        data class Completed(val requestOpCode: Int, val responseCode: Int) : RacpResponse() {

            val successful get() = responseCode == RACP_SUCCESS || responseCode == RACP_NO_RECORDS_FOUND
        }

        data class NumberOfRecords(val count: Int) : RacpResponse()
        data class Unknown(val bytes: ByteArray) : RacpResponse()
    }

    fun parseRacp(bytes: ByteArray): RacpResponse =
        when {
            bytes.size >= 4 && u8(bytes, 0) == RACP_RESPONSE_CODE        -> RacpResponse.Completed(u8(bytes, 2), u8(bytes, 3))
            bytes.size >= 4 && u8(bytes, 0) == RACP_NUMBER_OF_RECORDS_RESPONSE -> RacpResponse.NumberOfRecords(u16(bytes, 2))
            else                                                         -> RacpResponse.Unknown(bytes)
        }

    data class Measurement(
        val sequence: Int,
        /** Meter wall-clock time of the sample, base time + time offset. No time zone. */
        val time: LocalDateTime,
        /** null when the meter sent a special value (NaN, NRes, ±INF) */
        val mgdl: Double?,
        /** null when the meter sent a special value (NaN, NRes, ±INF) */
        val mmol: Double?,
        /** true when the meter reported in mol/L (the reading's native unit is mmol/L) */
        val nativeMmol: Boolean,
        val sampleType: Int?,
        val sampleLocation: Int?,
        val sensorStatus: Int?,
        val contextFollows: Boolean
    ) {

        val isControlSolution: Boolean
            get() = sampleType == SAMPLE_TYPE_CONTROL_SOLUTION || sampleLocation == SAMPLE_LOCATION_CONTROL_SOLUTION

        /** Sensor status annunciation bit 5 */
        val isAboveRange: Boolean get() = sensorStatus?.let { it and (1 shl 5) != 0 } == true

        /** Sensor status annunciation bit 6 */
        val isBelowRange: Boolean get() = sensorStatus?.let { it and (1 shl 6) != 0 } == true
    }

    /** @return null if the packet is too short to be a Glucose Measurement */
    fun parseMeasurement(bytes: ByteArray): Measurement? {
        if (bytes.size < 10) return null
        val flags = u8(bytes, 0)
        val timeOffsetPresent = flags and 0x01 != 0
        val concentrationPresent = flags and 0x02 != 0
        val unitMolPerL = flags and 0x04 != 0
        val statusPresent = flags and 0x08 != 0
        val contextFollows = flags and 0x10 != 0

        val sequence = u16(bytes, 1)
        val base = parseDateTime(bytes, 3) ?: return null
        var ptr = 10

        var time = base
        if (timeOffsetPresent) {
            if (bytes.size < ptr + 2) return null
            time = base.plusMinutes(s16(bytes, ptr).toLong())
            ptr += 2
        }

        var mgdl: Double? = null
        var mmol: Double? = null
        var sampleType: Int? = null
        var sampleLocation: Int? = null
        if (concentrationPresent) {
            if (bytes.size < ptr + 3) return null
            val value = sfloat(u16(bytes, ptr))
            ptr += 2
            if (value != null) {
                if (unitMolPerL) {
                    mmol = value * 1000.0
                    mgdl = mmol * MMOLL_TO_MGDL
                } else {
                    mgdl = value * 100_000.0
                    mmol = mgdl / MMOLL_TO_MGDL
                }
            }
            val typeLocation = u8(bytes, ptr)
            ptr += 1
            sampleType = typeLocation and 0x0F
            sampleLocation = (typeLocation shr 4) and 0x0F
        }

        var sensorStatus: Int? = null
        if (statusPresent && bytes.size >= ptr + 2) sensorStatus = u16(bytes, ptr)

        return Measurement(
            sequence = sequence,
            time = time,
            mgdl = mgdl,
            mmol = mmol,
            nativeMmol = unitMolPerL,
            sampleType = sampleType,
            sampleLocation = sampleLocation,
            sensorStatus = sensorStatus,
            contextFollows = contextFollows
        )
    }

    /**
     * Parse a Date Time (0x2A08) or the leading Date Time of a Current Time (0x2A2B) value.
     * @return null if the bytes are too short or the date is not set / invalid
     */
    fun parseDateTime(bytes: ByteArray, offset: Int = 0): LocalDateTime? {
        if (bytes.size < offset + 7) return null
        val year = u16(bytes, offset)
        val month = u8(bytes, offset + 2)
        val day = u8(bytes, offset + 3)
        val hour = u8(bytes, offset + 4)
        val minute = u8(bytes, offset + 5)
        val second = u8(bytes, offset + 6)
        // year 0 / month 0 / day 0 mean "not known"
        return runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull()
    }

    /**
     * IEEE 11073 16-bit SFLOAT: 4-bit signed exponent, 12-bit signed mantissa.
     * @return null for the special values NaN, NRes, +INF, -INF and reserved
     */
    fun sfloat(raw: Int): Double? {
        when (raw) {
            0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801 -> return null
        }
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = (raw shr 12) and 0x0F
        if (exponent >= 0x08) exponent -= 0x10
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()
}
