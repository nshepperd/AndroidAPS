package app.aaps.pump.rileylink2.ble

import java.util.UUID

/**
 * GATT surface of ble_rfspy 2.0 / OrangeLink (firmware ground truth: design doc §2.1).
 */
object GattUuids {

    val RADIO_SERVICE: UUID = UUID.fromString("0235733b-99c5-4197-b856-69219c2a3845")

    /** READ/WRITE, ≤150 bytes, NO notify — responses are read back (§2.5). */
    val CHARA_RADIO_DATA: UUID = UUID.fromString("c842e849-5028-42e2-867c-016adada9155")

    /** READ/NOTIFY, 1 byte; increments per queued response; wraps 0xFF→0x00; resets to 0 on reboot. */
    val CHARA_RADIO_RESPONSE_COUNT: UUID = UUID.fromString("6e6c7910-b89e-43a5-a0fe-50c5e2b81f4a")

    /** READ-only constant "ble_rfspy 2.0". */
    val CHARA_RADIO_VERSION: UUID = UUID.fromString("30d99dc9-7c91-4295-a051-0a104d238cf2")

    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val CHARA_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** OrangeLink extra service (Nordic UART UUID space); firmware/hardware version notify. */
    val ORANGE_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHARA_NOTIFICATION_ORANGE: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    fun of(char: CharId): UUID = when (char) {
        CharId.RADIO_DATA           -> CHARA_RADIO_DATA
        CharId.RADIO_RESPONSE_COUNT -> CHARA_RADIO_RESPONSE_COUNT
        CharId.RADIO_VERSION        -> CHARA_RADIO_VERSION
        CharId.BATTERY_LEVEL        -> CHARA_BATTERY_LEVEL
        CharId.ORANGE_NOTIFICATION  -> CHARA_NOTIFICATION_ORANGE
    }
}
