package app.aaps.pump.medtronic.service

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.keys.RileylinkBooleanPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedtronicUIComm
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.medtronic.defs.MedtronicDeviceType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.keys.MedtronicIntPreferenceKey
import app.aaps.pump.medtronic.keys.MedtronicStringPreferenceKey
import app.aaps.pump.medtronic.util.MedtronicUtil
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/**
 * Tests for MedtronicPumpLinkService.verifyConfiguration (ported from the legacy
 * RileyLinkMedtronicService tests; the supervisor restart is stubbed out and counted).
 */
class MedtronicPumpLinkServiceUTest : TestBaseWithProfile() {

    @Mock lateinit var rileyLinkUtil: RileyLinkUtil
    @Mock lateinit var medtronicPumpPlugin: MedtronicPumpPlugin
    @Mock lateinit var medtronicUtil: MedtronicUtil
    @Mock lateinit var medtronicCommunicationManager: MedtronicCommunicationManager
    @Mock lateinit var medtronicUIComm: MedtronicUIComm

    private class TestableService : MedtronicPumpLinkService() {

        var supervisorRestarts = 0
        override fun restartSupervisor() {
            supervisorRestarts++
        }
    }

    private lateinit var service: TestableService
    lateinit var medtronicPumpStatus: MedtronicPumpStatus
    lateinit var rileyLinkServiceData: RileyLinkServiceData

    @BeforeEach
    fun setup() {
        medtronicPumpStatus = MedtronicPumpStatus(preferences, rxBus, rileyLinkUtil)
        rileyLinkServiceData = RileyLinkServiceData(aapsLogger, rileyLinkUtil, rxBus)

        service = TestableService().also {
            it.medtronicPumpPlugin = medtronicPumpPlugin
            it.medtronicUtil = medtronicUtil
            it.medtronicPumpStatus = medtronicPumpStatus
            it.medtronicCommunicationManager = medtronicCommunicationManager
            it.medtronicUIComm = medtronicUIComm
            it.aapsLogger = aapsLogger
            it.preferences = preferences
            it.rh = rh
            it.rileyLinkServiceData = rileyLinkServiceData
        }
        doReturn("").whenever(rh).gs(any<Int>())
        doReturn("").whenever(rh).gs(any<Int>(), any())
    }

    private fun setupValidConfiguration() {
        whenever(preferences.get(MedtronicStringPreferenceKey.Serial)).thenReturn("123456")
        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("522")
        whenever(preferences.get(MedtronicStringPreferenceKey.PumpFrequency)).thenReturn("medtronic_pump_frequency_us_ca")
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("AA:BB:CC:DD:EE:FF")
        whenever(preferences.get(RileyLinkStringKey.Name)).thenReturn("RileyLink")
        whenever(preferences.get(MedtronicIntPreferenceKey.MaxBolus)).thenReturn(10)
        whenever(preferences.get(MedtronicIntPreferenceKey.MaxBasal)).thenReturn(5)
        whenever(preferences.get(MedtronicStringPreferenceKey.BatteryType)).thenReturn("medtronic_pump_battery_alkaline")
        whenever(preferences.get(RileylinkBooleanPreferenceKey.ShowReportedBatteryLevel)).thenReturn(true)
    }

    @Test
    fun `test verifyConfiguration with valid configuration returns true`() {
        setupValidConfiguration()

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isTrue()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("-")
        assertThat(medtronicPumpStatus.serialNumber).isEqualTo("123456")
        assertThat(medtronicPumpStatus.pumpType).isEqualTo(PumpType.MEDTRONIC_522_722)
        assertThat(medtronicPumpStatus.medtronicDeviceType).isEqualTo(MedtronicDeviceType.Medtronic_522)
        assertThat(medtronicPumpStatus.maxBolus).isEqualTo(10.0)
        assertThat(medtronicPumpStatus.maxBasal).isEqualTo(5.0)
        assertThat(medtronicPumpStatus.batteryType).isEqualTo(BatteryType.Alkaline)
        assertThat(rileyLinkServiceData.rileyLinkTargetFrequency).isEqualTo(RileyLinkTargetFrequency.MedtronicUS)
        assertThat(service.supervisorRestarts).isEqualTo(1)      // no supervisor yet → started
    }

    @Test
    fun `test verifyConfiguration with invalid serial number format returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(MedtronicStringPreferenceKey.Serial)).thenReturn("12345") // Too short
        whenever(rh.gs(R.string.medtronic_error_serial_invalid)).thenReturn("Invalid serial number")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Invalid serial number")
        assertThat(service.supervisorRestarts).isEqualTo(0)
    }

    @Test
    fun `test verifyConfiguration with non-numeric serial number returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(MedtronicStringPreferenceKey.Serial)).thenReturn("ABCDEF")
        whenever(rh.gs(R.string.medtronic_error_serial_invalid)).thenReturn("Invalid serial number")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Invalid serial number")
    }

    @Test
    fun `test verifyConfiguration with empty pump type returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("")
        whenever(rh.gs(R.string.medtronic_error_pump_type_not_set)).thenReturn("Pump type not set")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Pump type not set")
    }

    @Test
    fun `test verifyConfiguration with invalid pump type format returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("ABC")
        whenever(rh.gs(R.string.medtronic_error_pump_type_invalid)).thenReturn("Invalid pump type")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Invalid pump type")
    }

    @Test
    fun `test verifyConfiguration with unknown pump type returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("999")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
    }

    @Test
    fun `test verifyConfiguration with empty RileyLink address returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("")
        whenever(rh.gs(R.string.medtronic_error_rileylink_address_invalid)).thenReturn("Invalid RileyLink address")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Invalid RileyLink address")
    }

    @Test
    fun `test verifyConfiguration with invalid RileyLink MAC address format returns false`() {
        setupValidConfiguration()
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("INVALID_MAC")
        whenever(rh.gs(R.string.medtronic_error_rileylink_address_invalid)).thenReturn("Invalid RileyLink address")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Invalid RileyLink address")
    }

    @Test
    fun `test verifyConfiguration with valid RileyLink MAC address variations`() {
        setupValidConfiguration()

        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("AA:BB:CC:DD:EE:FF")
        assertThat(service.verifyConfiguration(forceRileyLinkAddressRenewal = false)).isTrue()

        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("aa:bb:cc:dd:ee:ff")
        assertThat(service.verifyConfiguration(forceRileyLinkAddressRenewal = false)).isTrue()

        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("0:1:2:3:4:5")
        assertThat(service.verifyConfiguration(forceRileyLinkAddressRenewal = false)).isTrue()
    }

    @Test
    fun `test verifyConfiguration sets reservoir size based on pump type`() {
        setupValidConfiguration()

        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("722")
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        assertThat(medtronicPumpStatus.reservoirFullUnits).isEqualTo(300)

        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("522")
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        assertThat(medtronicPumpStatus.reservoirFullUnits).isEqualTo(176)
    }

    @Test
    fun `test verifyConfiguration with all supported pump types`() {
        setupValidConfiguration()

        val pumpTypes = listOf(
            "512" to PumpType.MEDTRONIC_512_712,
            "712" to PumpType.MEDTRONIC_512_712,
            "515" to PumpType.MEDTRONIC_515_715,
            "715" to PumpType.MEDTRONIC_515_715,
            "522" to PumpType.MEDTRONIC_522_722,
            "722" to PumpType.MEDTRONIC_522_722,
            "523" to PumpType.MEDTRONIC_523_723_REVEL,
            "723" to PumpType.MEDTRONIC_523_723_REVEL,
            "554" to PumpType.MEDTRONIC_554_754_VEO,
            "754" to PumpType.MEDTRONIC_554_754_VEO
        )

        for ((pumpTypeCode, expectedPumpType) in pumpTypes) {
            whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn(pumpTypeCode)

            val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

            assertThat(result).isTrue()
            assertThat(medtronicPumpStatus.pumpType).isEqualTo(expectedPumpType)
        }
    }

    @Test
    fun `test verifyConfiguration with all supported battery types`() {
        setupValidConfiguration()

        val batteryTypes = listOf(
            "medtronic_pump_battery_no" to BatteryType.None,
            "medtronic_pump_battery_alkaline" to BatteryType.Alkaline,
            "medtronic_pump_battery_lithium" to BatteryType.Lithium,
            "medtronic_pump_battery_nizn" to BatteryType.NiZn,
            "medtronic_pump_battery_nimh" to BatteryType.NiMH
        )

        for ((batteryKey, expectedBatteryType) in batteryTypes) {
            whenever(preferences.get(MedtronicStringPreferenceKey.BatteryType)).thenReturn(batteryKey)

            service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

            assertThat(medtronicPumpStatus.batteryType).isEqualTo(expectedBatteryType)
        }
    }

    @Test
    fun `test verifyConfiguration updates max bolus when value changes`() {
        setupValidConfiguration()
        medtronicPumpStatus.maxBolus = 5.0
        whenever(preferences.get(MedtronicIntPreferenceKey.MaxBolus)).thenReturn(10)

        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(medtronicPumpStatus.maxBolus).isEqualTo(10.0)
    }

    @Test
    fun `test verifyConfiguration updates max basal when value changes`() {
        setupValidConfiguration()
        medtronicPumpStatus.maxBasal = 2.0
        whenever(preferences.get(MedtronicIntPreferenceKey.MaxBasal)).thenReturn(5)

        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(medtronicPumpStatus.maxBasal).isEqualTo(5.0)
    }

    @Test
    fun `test verifyConfiguration with exception returns false`() {
        whenever(preferences.get(MedtronicStringPreferenceKey.Serial)).thenThrow(RuntimeException("Test exception"))

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isFalse()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("Test exception")
    }

    @Test
    fun `test verifyConfiguration sets target frequency correctly`() {
        setupValidConfiguration()

        whenever(preferences.get(MedtronicStringPreferenceKey.PumpFrequency)).thenReturn("medtronic_pump_frequency_us_ca")
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        assertThat(rileyLinkServiceData.rileyLinkTargetFrequency).isEqualTo(RileyLinkTargetFrequency.MedtronicUS)

        whenever(preferences.get(MedtronicStringPreferenceKey.PumpFrequency)).thenReturn("medtronic_pump_frequency_worldwide")
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        assertThat(rileyLinkServiceData.rileyLinkTargetFrequency).isEqualTo(RileyLinkTargetFrequency.MedtronicWorldWide)
    }

    @Test
    fun `test verifyConfiguration sets show battery level flag`() {
        setupValidConfiguration()

        whenever(preferences.get(RileylinkBooleanPreferenceKey.ShowReportedBatteryLevel)).thenReturn(true)
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        assertThat(rileyLinkServiceData.showBatteryLevel).isTrue()

        whenever(preferences.get(RileylinkBooleanPreferenceKey.ShowReportedBatteryLevel)).thenReturn(false)
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        assertThat(rileyLinkServiceData.showBatteryLevel).isFalse()
    }

    @Test
    fun `test verifyConfiguration with pump type containing extra text`() {
        setupValidConfiguration()
        whenever(preferences.get(MedtronicStringPreferenceKey.PumpType)).thenReturn("522 (Paradigm 522)")

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isTrue()
        assertThat(medtronicPumpStatus.pumpType).isEqualTo(PumpType.MEDTRONIC_522_722)
    }

    @Test
    fun `test verifyConfiguration clears error description on success`() {
        setupValidConfiguration()
        medtronicPumpStatus.errorDescription = "Previous error"

        val result = service.verifyConfiguration(forceRileyLinkAddressRenewal = false)

        assertThat(result).isTrue()
        assertThat(medtronicPumpStatus.errorDescription).isEqualTo("-")
    }

    @Test
    fun `test force address renewal always restarts supervisor`() {
        setupValidConfiguration()
        service.verifyConfiguration(forceRileyLinkAddressRenewal = false)
        val restartsAfterFirst = service.supervisorRestarts

        service.verifyConfiguration(forceRileyLinkAddressRenewal = true)

        assertThat(service.supervisorRestarts).isEqualTo(restartsAfterFirst + 1)
    }
}
