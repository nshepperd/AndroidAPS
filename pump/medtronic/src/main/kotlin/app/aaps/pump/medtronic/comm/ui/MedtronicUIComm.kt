package app.aaps.pump.medtronic.comm.ui

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicUIResponseType
import app.aaps.pump.medtronic.util.MedtronicUtil
import app.aaps.pump.rileylink2.link.PumpCmdOutcome
import app.aaps.pump.rileylink2.link.PumpCmdPriority
import app.aaps.pump.rileylink2.link.PumpLinkSupervisor
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

/**
 * The single blocking↔suspend bridge of the Medtronic driver (rileylink2 conversion).
 *
 * The AAPS command queue calls [executeCommand] on its worker thread exactly as it did
 * with the legacy stack; internally the command runs as one [PumpLinkSupervisor.withPump]
 * block on the link actor. Everything above this class keeps blocking semantics;
 * everything below is suspend.
 */
class MedtronicUIComm @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val medtronicUtil: MedtronicUtil,
    private val medtronicUIPostprocessor: MedtronicUIPostprocessor,
    private val medtronicCommunicationManager: MedtronicCommunicationManager,
    private val medtronicUITaskProvider: Provider<MedtronicUITask>
) {

    /** Provided by MedtronicPumpLinkService once the supervisor exists. */
    var supervisorAccessor: () -> PumpLinkSupervisor? = { null }

    fun executeCommand(commandType: MedtronicCommandType): MedtronicUITask {
        return executeCommand(commandType, null)
    }

    @Synchronized
    fun executeCommand(commandType: MedtronicCommandType, parameters: ArrayList<Any>?): MedtronicUITask {

        aapsLogger.info(LTag.PUMP, "Execute Command: " + commandType.name)
        val task = medtronicUITaskProvider.get().with(commandType, parameters)
        medtronicUtil.setCurrentCommand(commandType)

        val supervisor = supervisorAccessor()
        if (supervisor == null) {
            aapsLogger.error(LTag.PUMP, "executeCommand: pump link not started, cannot execute $commandType")
            task.errorDescription = "Pump link not started"
            task.responseType = MedtronicUIResponseType.Error
        } else {
            val priority = if (commandType == MedtronicCommandType.SetBolus) PumpCmdPriority.HIGH else PumpCmdPriority.NORMAL
            val outcome = runBlocking {
                supervisor.withPump(commandType.name, priority = priority, readyTimeout = READY_TIMEOUT) { radio ->
                    task.execute(radio, medtronicCommunicationManager)
                }
            }
            when (outcome) {
                is PumpCmdOutcome.Success     -> Unit // task.result/responseType populated by execute()

                is PumpCmdOutcome.Unavailable -> {
                    aapsLogger.warn(LTag.PUMP, "executeCommand: link not Ready for $commandType (state=${outcome.stateAtRejection})")
                    task.errorDescription = "Pump link not ready: ${outcome.stateAtRejection}"
                    task.responseType = MedtronicUIResponseType.Error
                }

                is PumpCmdOutcome.Failed      -> {
                    aapsLogger.error(LTag.PUMP, "executeCommand: $commandType failed: ${outcome.error}")
                    task.errorDescription = outcome.error.message ?: outcome.error.javaClass.simpleName
                    task.responseType = MedtronicUIResponseType.Error
                }
            }
        }

        if (!task.isReceived) {
            aapsLogger.warn(LTag.PUMP, "Reply not received for $commandType")
        }

        task.postProcess(medtronicUIPostprocessor)
        return task
    }

    val invalidResponsesCount: Int
        get() = medtronicCommunicationManager.notConnectedCount

    companion object {

        /**
         * Budget for the link to reach Ready + execute one command. QueueWorker's own
         * ceiling is 120 s; staying under it lets the queue see clean failures.
         */
        private val READY_TIMEOUT = 90.seconds
    }
}
