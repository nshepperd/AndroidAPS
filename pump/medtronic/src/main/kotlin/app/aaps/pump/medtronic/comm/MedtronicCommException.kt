package app.aaps.pump.medtronic.comm

/**
 * Communication failure inside one pump exchange, thrown by the (rileylink2-backed)
 * MedtronicCommunicationManager. Module-private replacement for the legacy
 * RileyLinkCommunicationException so the proven retry structures keep their shape.
 */
class MedtronicCommException(val kind: Kind, message: String) : Exception(message) {

    enum class Kind {
        /** Pump did not answer within the listen window (radio-level timeout). */
        RADIO_TIMEOUT,

        /** Pump answered but the payload failed 4b6b decode or CRC (RF corruption). */
        GARBLED,

        /** Session/link-level problem: delivery uncertain or session lost mid-exchange. */
        LINK_TROUBLE,
    }
}
