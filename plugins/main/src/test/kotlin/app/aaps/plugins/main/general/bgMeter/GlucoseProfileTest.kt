package app.aaps.plugins.main.general.bgMeter

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class GlucoseProfileTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun parsesMmolMeasurementWithOffsetAndStatus() {
        val m = GlucoseProfile.parseMeasurement(
            bytes(
                0x0F,                                     // offset, concentration, mol/L, status
                0x02, 0x01,                               // sequence 258
                0xEA, 0x07, 10, 12, 8, 30, 15,            // 2026-10-12 08:30:15
                0x3C, 0x00,                               // +60 min
                0x3E, 0xC0,                               // 62e-4 mol/L = 6.2 mmol/L
                0x11,                                     // finger, capillary whole blood
                0x00, 0x00                                // status
            )
        )!!
        assertThat(m.sequence).isEqualTo(258)
        assertThat(m.time).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 30, 15))
        assertThat(m.nativeMmol).isTrue()
        assertThat(m.mmol).isWithin(1e-9).of(6.2)
        assertThat(m.mgdl).isWithin(0.1).of(111.7)
        assertThat(m.isControlSolution).isFalse()
        assertThat(m.isAboveRange).isFalse()
        assertThat(m.contextFollows).isFalse()
    }

    @Test
    fun parsesMgdlMeasurementWithoutOptionalFields() {
        val m = GlucoseProfile.parseMeasurement(
            bytes(
                0x12,                                     // concentration (kg/L), context follows
                0x05, 0x00,
                0xEA, 0x07, 1, 2, 3, 4, 5,
                0x78, 0xB0,                               // 120e-5 kg/L = 120 mg/dL
                0x11
            )
        )!!
        assertThat(m.sequence).isEqualTo(5)
        assertThat(m.time).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
        assertThat(m.nativeMmol).isFalse()
        assertThat(m.mgdl).isWithin(1e-9).of(120.0)
        assertThat(m.contextFollows).isTrue()
        assertThat(m.sensorStatus).isNull()
    }

    @Test
    fun flagsControlSolutionAndHiReadings() {
        val control = GlucoseProfile.parseMeasurement(bytes(0x06, 1, 0, 0xEA, 0x07, 1, 1, 0, 0, 0, 0x3E, 0xC0, 0x4A))!!
        assertThat(control.isControlSolution).isTrue()

        val hi = GlucoseProfile.parseMeasurement(bytes(0x0E, 1, 0, 0xEA, 0x07, 1, 1, 0, 0, 0, 0x4D, 0xC1, 0x11, 0x20, 0x00))!!
        assertThat(hi.isAboveRange).isTrue()
        assertThat(hi.isBelowRange).isFalse()
        assertThat(hi.mmol).isWithin(1e-9).of(33.3)
    }

    @Test
    fun specialValuesAndShortPacketsYieldNoValue() {
        assertThat(GlucoseProfile.sfloat(0x07FF)).isNull()
        assertThat(GlucoseProfile.sfloat(0x0800)).isNull()
        assertThat(GlucoseProfile.sfloat(0x0FFF)).isEqualTo(-1.0)
        assertThat(GlucoseProfile.parseMeasurement(bytes(0x02, 1, 0, 0xEA, 0x07, 1, 1, 0, 0, 0, 0xFF, 0x07, 0x11))!!.mgdl).isNull()
        assertThat(GlucoseProfile.parseMeasurement(bytes(0x02, 1, 0))).isNull()
        // month 0 = date not set
        assertThat(GlucoseProfile.parseDateTime(bytes(0xEA, 0x07, 0, 1, 0, 0, 0))).isNull()
    }

    @Test
    fun racpCommandsAndResponses() {
        assertThat(GlucoseProfile.racpReportAllRecords()).isEqualTo(bytes(0x01, 0x01))
        assertThat(GlucoseProfile.racpReportRecordsFrom(0x1234)).isEqualTo(bytes(0x01, 0x03, 0x01, 0x34, 0x12))

        val ok = GlucoseProfile.parseRacp(bytes(0x06, 0x00, 0x01, 0x01)) as GlucoseProfile.RacpResponse.Completed
        assertThat(ok.successful).isTrue()
        val none = GlucoseProfile.parseRacp(bytes(0x06, 0x00, 0x01, 0x06)) as GlucoseProfile.RacpResponse.Completed
        assertThat(none.successful).isTrue()
        val bad = GlucoseProfile.parseRacp(bytes(0x06, 0x00, 0x01, 0x04)) as GlucoseProfile.RacpResponse.Completed
        assertThat(bad.successful).isFalse()
        assertThat(GlucoseProfile.parseRacp(bytes(0x05, 0x00, 0x0A, 0x00))).isEqualTo(GlucoseProfile.RacpResponse.NumberOfRecords(10))
    }
}
