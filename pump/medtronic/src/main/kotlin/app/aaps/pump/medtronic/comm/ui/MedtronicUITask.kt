package app.aaps.pump.medtronic.comm.ui

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.pump.common.events.EventRileyLinkDeviceStatusChange
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.rileylink2.minimed.MinimedRadio
import app.aaps.pump.medtronic.comm.history.pump.PumpHistoryEntry
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.TempBasalPair
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicUIResponseType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.events.EventMedtronicPumpValuesChanged
import app.aaps.pump.medtronic.util.MedtronicUtil
import org.joda.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject

/**
 * Created by andy on 6/14/18.
 */
class MedtronicUITask @Inject constructor(
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger,
    private val medtronicPumpStatus: MedtronicPumpStatus,
    private val medtronicUtil: MedtronicUtil
) {

    lateinit var commandType: MedtronicCommandType
    var result: Any? = null
    var errorDescription: String? = null
    var parameters: List<Any?>? = null
    var responseType: MedtronicUIResponseType? = null

    fun with(commandType: MedtronicCommandType, parameters: List<Any>?): MedtronicUITask {
        this.commandType = commandType
        this.parameters = parameters //as Array<Any>
        return this
    }

    suspend fun execute(radio: MinimedRadio, communicationManager: MedtronicCommunicationManager) {
        aapsLogger.debug(LTag.PUMP, "MedtronicUITask: @@@ In execute. $commandType")
        when (commandType) {
            MedtronicCommandType.PumpModel                                   -> {
                result = communicationManager.getPumpModel(radio)
            }

            MedtronicCommandType.GetBasalProfileSTD                          -> {
                result = communicationManager.getBasalProfile(radio)
            }

            MedtronicCommandType.GetRemainingInsulin                         -> {
                result = communicationManager.getRemainingInsulin(radio)
            }

            MedtronicCommandType.GetRealTimeClock                            -> {
                result = communicationManager.getPumpTime(radio)
                //medtronicUtil.pumpTime = null
            }

            MedtronicCommandType.SetRealTimeClock                            -> {
                result = communicationManager.setPumpTime(radio)
            }

            MedtronicCommandType.GetBatteryStatus                            -> {
                result = communicationManager.getRemainingBattery(radio)
            }

            MedtronicCommandType.SetTemporaryBasal                           -> {
                val tbr = getTbrSettings()
                result = communicationManager.setTemporaryBasal(radio, tbr)
            }

            MedtronicCommandType.ReadTemporaryBasal                          -> {
                result = communicationManager.getTemporaryBasal(radio)
            }

            MedtronicCommandType.Settings, MedtronicCommandType.Settings_512 -> {
                result = communicationManager.getPumpSettings(radio)
            }

            MedtronicCommandType.SetBolus                                    -> {
                val amount = getDoubleFromParameters(0)
                if (amount != null) result = communicationManager.setBolus(radio, amount)
            }

            MedtronicCommandType.CancelTBR                                   -> {
                result = communicationManager.cancelTBR(radio)
            }

            MedtronicCommandType.SetBasalProfileSTD,
            MedtronicCommandType.SetBasalProfileA                            -> {
                val profile = parameters!![0] as BasalProfile
                result = communicationManager.setBasalProfile(radio, profile)
            }

            MedtronicCommandType.GetHistoryData                              -> {
                result = communicationManager.getPumpHistory(
                    radio,
                    parameters!![0] as PumpHistoryEntry?,
                    parameters!![1] as LocalDateTime?
                )
            }

            else                                                             -> {
                aapsLogger.warn(LTag.PUMP, String.format(Locale.ENGLISH, "This commandType is not supported (yet) - %s.", commandType))
                // invalid = true;
                responseType = MedtronicUIResponseType.Invalid
            }
        }
        if (responseType == null) {
            if (result == null) {
                errorDescription = communicationManager.errorResponse
                responseType = MedtronicUIResponseType.Error
            } else {
                responseType = MedtronicUIResponseType.Data
            }
        }
    }

    private fun getTbrSettings(): TempBasalPair {
        return TempBasalPair(
            getDoubleFromParameters(0)!!,  //
            false,  //
            getIntegerFromParameters(1)
        )
    }

    @Suppress("unused")
    private fun getFloatFromParameters(index: Int): Float {
        return parameters!![index] as Float
    }

    fun getDoubleFromParameters(index: Int): Double? {
        return parameters!![index] as Double?
    }

    private fun getIntegerFromParameters(@Suppress("SameParameterValue") index: Int): Int {
        return parameters!![index] as Int
    }

    val isReceived: Boolean
        get() = result != null || errorDescription != null

    fun postProcess(postprocessor: MedtronicUIPostprocessor) {
        aapsLogger.debug(LTag.PUMP, "MedtronicUITask: @@@ In execute. $commandType")
        if (responseType === MedtronicUIResponseType.Data) {
            postprocessor.postProcessData(this)
        }
        if (responseType === MedtronicUIResponseType.Invalid) {
            rxBus.send(
                EventRileyLinkDeviceStatusChange(
                    PumpDeviceState.ErrorWhenCommunicating,
                    "Unsupported command in MedtronicUITask"
                )
            )
        } else if (responseType === MedtronicUIResponseType.Error) {
            rxBus.send(
                EventRileyLinkDeviceStatusChange(
                    PumpDeviceState.ErrorWhenCommunicating,
                    errorDescription
                )
            )
        } else {
            rxBus.send(EventMedtronicPumpValuesChanged())
            medtronicPumpStatus.setLastCommunicationToNow()
        }
        medtronicUtil.setCurrentCommand(null)
    }

    fun hasData(): Boolean = responseType == MedtronicUIResponseType.Data

    fun getParameter(index: Int): Any? = parameters!![index]
}