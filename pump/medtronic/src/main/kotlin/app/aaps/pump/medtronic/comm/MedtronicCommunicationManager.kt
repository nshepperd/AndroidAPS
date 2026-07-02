package app.aaps.pump.medtronic.comm

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.DateTimeUtil
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkLongKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.medtronic.comm.history.RawHistoryPage
import app.aaps.pump.medtronic.comm.history.pump.MedtronicPumpHistoryDecoder
import app.aaps.pump.medtronic.comm.history.pump.PumpHistoryEntry
import app.aaps.pump.medtronic.comm.history.pump.PumpHistoryResult
import app.aaps.pump.medtronic.comm.message.CarelinkLongMessageBody
import app.aaps.pump.medtronic.comm.message.CarelinkShortMessageBody
import app.aaps.pump.medtronic.comm.message.GetHistoryPageCarelinkMessageBody
import app.aaps.pump.medtronic.comm.message.MessageBody
import app.aaps.pump.medtronic.comm.message.PacketType
import app.aaps.pump.medtronic.comm.message.PumpAckMessageBody
import app.aaps.pump.medtronic.comm.message.PumpMessage
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.BatteryStatusDTO
import app.aaps.pump.medtronic.data.dto.ClockDTO
import app.aaps.pump.medtronic.data.dto.PumpSettingDTO
import app.aaps.pump.medtronic.data.dto.TempBasalPair
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicCommandType.Companion.getSettings
import app.aaps.pump.medtronic.defs.MedtronicDeviceType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.util.MedtronicUtil
import app.aaps.pump.medtronic.util.MedtronicUtil.Companion.createByteArray
import app.aaps.pump.medtronic.util.MedtronicUtil.Companion.getByteArrayFromUnsignedShort
import app.aaps.pump.rileylink2.minimed.MinimedRadio
import kotlinx.coroutines.delay
import org.joda.time.LocalDateTime
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Original file created by geoff on 5/30/16; heavily extended by Andy Rozman.
 *
 * rileylink2 conversion (2026-07): the pump PROTOCOL logic in this class — message shapes,
 * retry budgets, multi-frame flows, state transitions — is preserved verbatim from the
 * legacy (proven) implementation. Only the transport changed: every exchange now goes
 * through [MinimedRadio] on the caller-provided radio session, methods are suspend, sleeps
 * became delays, and [MedtronicCommException] replaces RileyLinkCommunicationException as
 * the intra-command control flow.
 *
 * NOTE (design I9 follow-up): like the legacy stack, set-commands (including SetBolus) are
 * retried on radio timeout. A timeout after a delivered-but-unacknowledged command is the
 * pump-level uncertainty window; reconciliation still happens via pump history. Tightening
 * this beyond legacy behavior is deliberately out of scope for the transport conversion.
 */
@Singleton
class MedtronicCommunicationManager @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val medtronicPumpStatus: MedtronicPumpStatus,
    private val medtronicConverter: MedtronicConverter,
    private val medtronicUtil: MedtronicUtil,
    private val medtronicPumpHistoryDecoder: MedtronicPumpHistoryDecoder,
    private val rileyLinkServiceData: RileyLinkServiceData,
) {

    companion object {

        private const val MAX_COMMAND_TRIES = 3
        private const val DEFAULT_TIMEOUT = 2000

        /** Pump stays awake ~this long after a wake burst; wakeUp() is cached on it. */
        private const val RECEIVER_DEVICE_AWAKE_FOR_MINUTES = 1
    }

    var errorResponse: String? = null
        private set
    private val debugSetCommands = false
    private var doWakeUpBeforeCommand = true
    private var nextWakeUpRequired = 0L

    /** Diagnostic counter (legacy notConnectedCount equivalent): timeouts since last success. */
    var notConnectedCount = 0
        private set

    fun setDoWakeUpBeforeCommand(doWakeUp: Boolean) {
        doWakeUpBeforeCommand = doWakeUp
    }

    // ---------------------------------------------------------------- reachability ----

    /**
     * Wake the pump and verify it answers with a valid model (legacy isDeviceReachable).
     * No tune scheduling here — the caller escalates via PumpLinkSupervisor.
     */
    suspend fun verifyPumpPresence(radio: MinimedRadio): Boolean {
        val state = medtronicPumpStatus.pumpDeviceState
        if (state !== PumpDeviceState.PumpUnreachable) medtronicPumpStatus.pumpDeviceState = PumpDeviceState.WakingUp
        for (retry in 0..4) {
            aapsLogger.debug(LTag.PUMPCOMM, "verifyPumpPresence. Waking pump... " + if (retry != 0) " (retry $retry)" else "")
            if (probeForPumpModel(radio)) return true
            delay(1000)
        }
        if (state !== PumpDeviceState.PumpUnreachable) medtronicPumpStatus.pumpDeviceState = PumpDeviceState.PumpUnreachable
        return false
    }

    private suspend fun probeForPumpModel(radio: MinimedRadio): Boolean {
        val state = medtronicPumpStatus.pumpDeviceState
        val probe = createWakeMessageContent()
        when (val outcome = radio.wake(probe)) {
            is MinimedRadio.RadioOutcome.Reply       -> {
                val pumpResponse = PumpMessage(aapsLogger, outcome.message)
                if (!pumpResponse.isValid()) {
                    aapsLogger.warn(LTag.PUMPCOMM, "probeForPumpModel: invalid pump message")
                    return false
                }
                val pumpModel = medtronicConverter.decodeModel(pumpResponse.rawContent) as MedtronicDeviceType?
                val valid = pumpModel !== MedtronicDeviceType.Unknown_Device
                if (!medtronicUtil.isModelSet && valid) {
                    medtronicUtil.medtronicPumpModel = pumpModel!!
                    medtronicUtil.isModelSet = true
                }
                aapsLogger.debug(
                    LTag.PUMPCOMM,
                    String.format(Locale.ENGLISH, "probeForPumpModel. PumpModel is %s - Valid: %b (rssi=%d)", medtronicUtil.medtronicPumpModel, valid, outcome.rssiDbm)
                )
                if (valid) {
                    medtronicPumpStatus.pumpDeviceState =
                        if (state === PumpDeviceState.PumpUnreachable) PumpDeviceState.WakingUp else PumpDeviceState.Sleeping
                    rememberLastGoodDeviceCommunicationTime()
                    nextWakeUpRequired = System.currentTimeMillis() + RECEIVER_DEVICE_AWAKE_FOR_MINUTES * 60L * 1000L
                    return true
                }
                if (state !== PumpDeviceState.PumpUnreachable) medtronicPumpStatus.pumpDeviceState = PumpDeviceState.PumpUnreachable
                return false
            }

            is MinimedRadio.RadioOutcome.Garbled     -> {
                // pump present but payload unusable (legacy: Unknown_Device path) — retry
                aapsLogger.warn(LTag.PUMPCOMM, "probeForPumpModel: garbled response (rssi=${outcome.rssiDbm})")
                return false
            }

            is MinimedRadio.RadioOutcome.PumpSilent  -> {
                aapsLogger.error(LTag.PUMPCOMM, "probeForPumpModel: Failed to find pump (timeout).")
                return false
            }

            is MinimedRadio.RadioOutcome.LinkTrouble -> {
                aapsLogger.error(LTag.PUMPCOMM, "probeForPumpModel: link trouble: ${outcome.detail}")
                return false
            }
        }
    }

    // ---------------------------------------------------------------- transport core ----

    private fun createWakeMessageContent(): ByteArray =
        medtronicUtil.buildCommandPayload(rileyLinkServiceData, MedtronicCommandType.PumpModel, null)

    /** Keep the pump radio awake; cached like the legacy wakeUp (1-minute window). */
    private suspend fun wakeUp(radio: MinimedRadio, force: Boolean = false) {
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.WakingUp
        if (force) nextWakeUpRequired = 0L
        if (System.currentTimeMillis() > nextWakeUpRequired) {
            aapsLogger.info(LTag.PUMPCOMM, "Waking pump...")
            val outcome = radio.wake(createWakeMessageContent())
            aapsLogger.info(LTag.PUMPCOMM, "wakeup outcome: $outcome")
            nextWakeUpRequired = System.currentTimeMillis() + RECEIVER_DEVICE_AWAKE_FOR_MINUTES * 60L * 1000L
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "Last pump communication was recent, not waking pump.")
        }
    }

    @Throws(MedtronicCommException::class)
    private suspend fun sendAndListen(radio: MinimedRadio, msg: PumpMessage, timeoutMs: Int = 4000): PumpMessage {
        aapsLogger.info(LTag.PUMPBTCOMM, "Sent:" + ByteUtil.shortHexString(msg.getTxData()))
        when (val outcome = radio.sendAndListen(msg.getTxData(), listenTimeoutMs = timeoutMs)) {
            is MinimedRadio.RadioOutcome.Reply       -> {
                val response = PumpMessage(aapsLogger, outcome.message)
                if (response.isValid()) {
                    rememberLastGoodDeviceCommunicationTime()
                    notConnectedCount = 0
                } else {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "sendAndListen: response message invalid")
                }
                aapsLogger.info(LTag.PUMPBTCOMM, "Received:" + ByteUtil.shortHexString(outcome.message))
                return response
            }

            is MinimedRadio.RadioOutcome.PumpSilent  -> {
                notConnectedCount++
                throw MedtronicCommException(MedtronicCommException.Kind.RADIO_TIMEOUT, "pump silent")
            }

            is MinimedRadio.RadioOutcome.Garbled     -> {
                notConnectedCount++
                throw MedtronicCommException(MedtronicCommException.Kind.GARBLED, "garbled response (rssi=${outcome.rssiDbm})")
            }

            is MinimedRadio.RadioOutcome.LinkTrouble -> {
                notConnectedCount++
                throw MedtronicCommException(MedtronicCommException.Kind.LINK_TROUBLE, outcome.detail)
            }
        }
    }

    private fun rememberLastGoodDeviceCommunicationTime() {
        val now = System.currentTimeMillis()
        preferences.put(RileyLinkLongKey.LastGoodDeviceCommunicationTime, now)
        medtronicPumpStatus.setLastCommunicationToNow()
    }

    // ---------------------------------------------------------------- command scaffolding ----

    @Throws(MedtronicCommException::class)
    private suspend fun runCommandWithArgs(radio: MinimedRadio, msg: PumpMessage): PumpMessage {
        if (debugSetCommands) aapsLogger.debug(LTag.PUMPCOMM, "Run command with Args: ")
        val shortMessage = makePumpMessage(msg.commandType, CarelinkShortMessageBody(byteArrayOf(0)))
        // look for ack from short message
        val shortResponse = sendAndListen(radio, shortMessage)
        return if (shortResponse.commandType === MedtronicCommandType.CommandACK) {
            if (debugSetCommands) aapsLogger.debug(LTag.PUMPCOMM, "Run command with Args: Got ACK response")
            val rVal = sendAndListen(radio, msg)
            if (debugSetCommands) aapsLogger.debug(LTag.PUMPCOMM, "2nd Response: $rVal")
            rVal
        } else {
            aapsLogger.error(LTag.PUMPCOMM, "runCommandWithArgs: Pump did not ack Attention packet")
            PumpMessage(aapsLogger, "No ACK after Attention packet.")
        }
    }

    @Suppress("SameParameterValue")
    @Throws(MedtronicCommException::class)
    private suspend fun runCommandWithFrames(radio: MinimedRadio, commandType: MedtronicCommandType, frames: List<List<Byte>>): PumpMessage? {
        aapsLogger.debug(LTag.PUMPCOMM, "Run command with Frames: " + commandType.name)
        var rVal: PumpMessage? = null
        val shortMessage = makePumpMessage(commandType, CarelinkShortMessageBody(byteArrayOf(0)))
        // look for ack from short message
        val shortResponse = sendAndListen(radio, shortMessage)
        if (shortResponse.commandType !== MedtronicCommandType.CommandACK) {
            aapsLogger.error(LTag.PUMPCOMM, "runCommandWithFrames: Pump did not ack Attention packet")
            return PumpMessage(aapsLogger, "No ACK after start message.")
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "Run command with Frames: Got ACK response for Attention packet")
        }
        var frameNr = 1
        for (frame in frames) {
            val frameData = createByteArray(frame)
            val msg = makePumpMessage(commandType, CarelinkLongMessageBody(frameData))
            rVal = sendAndListen(radio, msg)
            if (rVal.commandType !== MedtronicCommandType.CommandACK) {
                aapsLogger.error(LTag.PUMPCOMM, "runCommandWithFrames: Pump did not ACK frame #$frameNr")
                aapsLogger.error(
                    LTag.PUMPCOMM,
                    String.format(Locale.ENGLISH, "Run command with Frames FAILED (command=%s, response=%s)", commandType.name, rVal.toString())
                )
                return PumpMessage(aapsLogger, "No ACK after frame #$frameNr")
            } else {
                aapsLogger.debug(LTag.PUMPCOMM, "Run command with Frames: Got ACK response for frame #$frameNr")
            }
            frameNr++
        }
        return rVal
    }

    suspend fun getPumpHistory(radio: MinimedRadio, lastEntry: PumpHistoryEntry?, targetDate: LocalDateTime?): PumpHistoryResult {
        val pumpTotalResult = PumpHistoryResult(aapsLogger, lastEntry, if (targetDate == null) null else DateTimeUtil.toATechDate(targetDate))
        if (doWakeUpBeforeCommand) wakeUp(radio)
        aapsLogger.debug(LTag.PUMPCOMM, "Current command: " + medtronicUtil.getCurrentCommand())
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Active
        var doneWithError = false
        for (pageNumber in 0..4) {
            val rawHistoryPage = RawHistoryPage(aapsLogger)
            val getHistoryMsg = makePumpMessage(
                MedtronicCommandType.GetHistoryData,
                GetHistoryPageCarelinkMessageBody(pageNumber)
            )
            aapsLogger.info(LTag.PUMPCOMM, "getPumpHistory: Page $pageNumber")
            // Ask the pump to transfer history (we get first frame?)
            var firstResponse: PumpMessage? = null
            var failed = false
            medtronicUtil.setCurrentCommand(MedtronicCommandType.GetHistoryData, pageNumber, null)
            for (retries in 0 until MAX_COMMAND_TRIES) {
                try {
                    firstResponse = runCommandWithArgs(radio, getHistoryMsg)
                    failed = false
                    break
                } catch (_: MedtronicCommException) {
                    aapsLogger.error(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "First call for PumpHistory failed (retry=%d)", retries))
                    failed = true
                }
            }
            if (failed) {
                medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
                return pumpTotalResult
            }
            val ackMsg = makePumpMessage(MedtronicCommandType.CommandACK, PumpAckMessageBody())
            var currentResponse = GetHistoryPageCarelinkMessageBody(firstResponse!!.messageBody!!.txData)
            var expectedFrameNum = 1
            var done = false
            var failures = 0
            while (!done) {
                // examine current response for problems.
                val frameData = currentResponse.frameData
                if (frameData.isNotEmpty() && currentResponse.frameNumber == expectedFrameNum) {
                    // success! got a frame.
                    if (frameData.size != 64) {
                        aapsLogger.warn(LTag.PUMPCOMM, "Expected frame of length 64, got frame of length " + frameData.size)
                        // but append it anyway?
                    }
                    // handle successful frame data
                    rawHistoryPage.appendData(currentResponse.frameData)
                    medtronicUtil.setCurrentCommand(MedtronicCommandType.GetHistoryData, pageNumber, currentResponse.frameNumber)
                    aapsLogger.info(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "getPumpHistory: Got frame %d of Page %d", currentResponse.frameNumber, pageNumber))
                    // Do we need to ask for the next frame?
                    if (expectedFrameNum < 16) { // This number may not be correct for pumps other than 522/722
                        expectedFrameNum++
                    } else {
                        done = true // successful completion
                    }
                } else {
                    if (frameData.isEmpty()) {
                        aapsLogger.error(LTag.PUMPCOMM, "null frame data, retrying")
                    } else if (currentResponse.frameNumber != expectedFrameNum) {
                        aapsLogger.warn(
                            LTag.PUMPCOMM,
                            String.format(Locale.ENGLISH, "Expected frame number %d, received %d (retrying)", expectedFrameNum, currentResponse.frameNumber)
                        )
                    }
                    failures++
                    if (failures == 6) {
                        aapsLogger.error(
                            LTag.PUMPCOMM,
                            String.format(Locale.ENGLISH, "getPumpHistory: 6 failures in attempting to download frame %d of page %d, giving up.", expectedFrameNum, pageNumber)
                        )
                        done = true // failure completion.
                        doneWithError = true
                    }
                }
                if (!done) {
                    // ask for next frame
                    var nextMsg: PumpMessage? = null
                    for (retries in 0 until MAX_COMMAND_TRIES) {
                        try {
                            nextMsg = sendAndListen(radio, ackMsg)
                            break
                        } catch (_: MedtronicCommException) {
                            aapsLogger.error(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Problem acknowledging frame response. (retry=%d)", retries))
                        }
                    }
                    if (nextMsg != null)
                        currentResponse = GetHistoryPageCarelinkMessageBody(nextMsg.messageBody!!.txData)
                    else {
                        aapsLogger.error(LTag.PUMPCOMM, "We couldn't acknowledge frame from pump, aborting operation.")
                    }
                }
            }
            if (rawHistoryPage.length != 1024) {
                aapsLogger.warn(LTag.PUMPCOMM, "getPumpHistory: short page.  Expected length of 1024, found length of " + rawHistoryPage.length)
                doneWithError = true
            }
            if (!rawHistoryPage.isChecksumOK) {
                aapsLogger.error(LTag.PUMPCOMM, "getPumpHistory: checksum is wrong")
                doneWithError = true
            }
            if (doneWithError) {
                medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
                return pumpTotalResult
            }
            rawHistoryPage.dumpToDebug()
            val medtronicHistoryEntries = medtronicPumpHistoryDecoder.processPageAndCreateRecords(rawHistoryPage)
            aapsLogger.debug(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "getPumpHistory: Found %d history entries.", medtronicHistoryEntries.size))
            pumpTotalResult.addHistoryEntries(medtronicHistoryEntries)
            aapsLogger.debug(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "getPumpHistory: Search status: Search finished: %b", pumpTotalResult.isSearchFinished))
            if (pumpTotalResult.isSearchFinished) {
                medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
                return pumpTotalResult
            }
        }
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
        return pumpTotalResult
    }

    private fun makePumpMessage(messageType: MedtronicCommandType, body: ByteArray? = null): PumpMessage {
        return makePumpMessage(messageType, body?.let { CarelinkShortMessageBody(it) }
            ?: CarelinkShortMessageBody())
    }

    private fun makePumpMessage(messageType: MedtronicCommandType?, messageBody: MessageBody): PumpMessage {
        val msg = PumpMessage(aapsLogger)
        msg.init(PacketType.Carelink, rileyLinkServiceData.pumpIDBytes, messageType, messageBody)
        return msg
    }

    /**
     * Main wrapper method for sending data - (for getting responses)
     */
    @Throws(MedtronicCommException::class)
    private suspend fun sendAndGetResponse(radio: MinimedRadio, commandType: MedtronicCommandType, bodyData: ByteArray? = null, timeoutMs: Int = DEFAULT_TIMEOUT): PumpMessage {
        // wakeUp
        if (doWakeUpBeforeCommand) wakeUp(radio)
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Active

        // create message
        val msg: PumpMessage = bodyData?.let { makePumpMessage(commandType, it) } ?: makePumpMessage(commandType)

        // send and wait for response
        val response = sendAndListen(radio, msg, timeoutMs)
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
        return response
    }

    private suspend inline fun <reified T> sendAndGetResponseWithCheck(
        radio: MinimedRadio,
        commandType: MedtronicCommandType,
        bodyData: ByteArray? = null,
        decode: (pumpType: PumpType, commandType: MedtronicCommandType, rawContent: ByteArray) -> T
    ): T? {
        aapsLogger.debug(LTag.PUMPCOMM, "getDataFromPump: $commandType")
        for (retries in 0 until MAX_COMMAND_TRIES) {
            try {
                val response = sendAndGetResponse(radio, commandType, bodyData, DEFAULT_TIMEOUT + DEFAULT_TIMEOUT * retries)
                val check = checkResponseContent(response, commandType.commandDescription, commandType.expectedLength)
                if (check == null) {

                    checkResponseRawContent(response.rawContent, commandType) { return@sendAndGetResponseWithCheck null }

                    val dataResponse = decode(medtronicPumpStatus.pumpType, commandType, response.rawContent)
                    if (dataResponse != null) {
                        errorResponse = null
                        aapsLogger.debug(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Converted response for %s is %s.", commandType.name, dataResponse))
                        return dataResponse
                    } else {
                        errorResponse = "Error decoding response."
                    }
                } else {
                    errorResponse = check
                }
            } catch (e: MedtronicCommException) {
                aapsLogger.warn(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Error getting response from pump (error=%s, retry=%d)", e.message, retries + 1))
            }
        }
        return null
    }

    private inline fun checkResponseRawContent(rawContent: ByteArray?, commandType: MedtronicCommandType, errorCase: () -> Unit) {
        if (rawContent?.isEmpty() != false && commandType != MedtronicCommandType.PumpModel) {
            aapsLogger.warn(
                LTag.PUMPCOMM, String.format(
                    Locale.ENGLISH, "Content is empty or too short, no data to convert (type=%s,isNull=%b,length=%s)",
                    commandType.name, rawContent == null, rawContent?.size ?: "-"
                )
            )
            errorCase.invoke()
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "Raw response before convert: " + ByteUtil.shortHexString(rawContent))
        }
    }

    private fun checkResponseContent(response: PumpMessage, method: String, expectedLength: Int): String? {
        if (!response.isValid()) {
            val responseData = String.format("%s: Invalid response.", method)
            aapsLogger.warn(LTag.PUMPCOMM, responseData)
            return responseData
        }
        val contents = response.rawContent
        return if (contents.isNotEmpty()) {
            if (contents.size >= expectedLength) {
                aapsLogger.debug(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "%s: Content: %s", method, ByteUtil.shortHexString(contents)))
                null
            } else {
                val responseData = String.format(
                    "%s: Cannot return data. Data is too short [expected=%s, received=%s].", method, ""
                        + expectedLength, "" + contents.size
                )
                aapsLogger.warn(LTag.PUMPCOMM, responseData)
                responseData
            }
        } else {
            val responseData = String.format("%s: Cannot return data. Zero length response.", method)
            aapsLogger.warn(LTag.PUMPCOMM, responseData)
            responseData
        }
    }

    // PUMP SPECIFIC COMMANDS
    suspend fun getRemainingInsulin(radio: MinimedRadio): Double? {
        return sendAndGetResponseWithCheck(radio, MedtronicCommandType.GetRemainingInsulin) { _, _, rawContent ->
            medtronicConverter.decodeRemainingInsulin(rawContent)
        }
    }

    suspend fun getPumpModel(radio: MinimedRadio): MedtronicDeviceType? {
        return sendAndGetResponseWithCheck(radio, MedtronicCommandType.PumpModel) { _, _, rawContent ->
            medtronicConverter.decodeModel(rawContent)
        }
    }

    suspend fun getBasalProfile(radio: MinimedRadio): BasalProfile? {

        // wakeUp
        if (doWakeUpBeforeCommand) wakeUp(radio)
        val commandType = MedtronicCommandType.GetBasalProfileSTD
        aapsLogger.debug(LTag.PUMPCOMM, "getDataFromPump: $commandType")
        medtronicUtil.setCurrentCommand(commandType)
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Active
        for (retries in 0..MAX_COMMAND_TRIES) {
            try {
                // create message
                val msg: PumpMessage = makePumpMessage(commandType)

                // send and wait for response
                var response = sendAndListen(radio, msg, DEFAULT_TIMEOUT + DEFAULT_TIMEOUT * retries)
                val check = checkResponseContent(response, commandType.commandDescription, 1)
                var data: ByteArray = byteArrayOf()
                if (check == null) {
                    data = response.rawContentOfFrame
                    val ackMsg = makePumpMessage(MedtronicCommandType.CommandACK, PumpAckMessageBody())
                    while (checkIfWeHaveMoreData(commandType, response, data)) {
                        response = sendAndListen(radio, ackMsg, DEFAULT_TIMEOUT + DEFAULT_TIMEOUT * retries)
                        val check2 = checkResponseContent(response, commandType.commandDescription, 1)
                        if (check2 == null) {
                            data = ByteUtil.concat(data, response.rawContentOfFrame)
                        } else {
                            errorResponse = check2
                            aapsLogger.error(LTag.PUMPCOMM, "Error with response got GetProfile: $check2")
                        }
                    }
                } else {
                    errorResponse = check
                }

                aapsLogger.debug(LTag.PUMPCOMM, "End Response: {}", ByteUtil.getHex(data))

                val basalProfile: BasalProfile? = medtronicConverter.decodeBasalProfile(medtronicPumpStatus.pumpType, data)

                if (basalProfile != null) {
                    aapsLogger.debug(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Converted response for %s is %s.", commandType.name, basalProfile))
                    medtronicUtil.setCurrentCommand(null)
                    medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
                    return basalProfile
                }
            } catch (e: MedtronicCommException) {
                aapsLogger.error(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Error getting response from pump (error=%s, retry=%d)", e.message, retries + 1))
            }
        }
        aapsLogger.warn(LTag.PUMPCOMM, "Error reading profile in max retries.")
        medtronicUtil.setCurrentCommand(null)
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.Sleeping
        return null
    }

    private fun checkIfWeHaveMoreData(commandType: MedtronicCommandType, response: PumpMessage, data: ByteArray): Boolean {
        if (commandType === MedtronicCommandType.GetBasalProfileSTD || //
            commandType === MedtronicCommandType.GetBasalProfileA || //
            commandType === MedtronicCommandType.GetBasalProfileB
        ) {
            val responseRaw = response.rawContentOfFrame
            val last = responseRaw.size - 1
            aapsLogger.debug(LTag.PUMPCOMM, "Length: " + data.size)
            if (data.size >= BasalProfile.MAX_RAW_DATA_SIZE) {
                return false
            }
            return if (responseRaw.size < 2) {
                false
            } else !(responseRaw[last] == 0x00.toByte() && responseRaw[last - 1] == 0x00.toByte() && responseRaw[last - 2] == 0x00.toByte())
        }
        return false
    }

    suspend fun getPumpTime(radio: MinimedRadio): ClockDTO? {
        val localTime = LocalDateTime()
        val responseObject = sendAndGetResponseWithCheck(radio, MedtronicCommandType.GetRealTimeClock) { _, _, rawContent ->
            medtronicConverter.decodeTime(rawContent)
        }
        if (responseObject != null) {
            return ClockDTO(localDeviceTime = localTime, pumpTime = responseObject)
        }
        return null
    }

    suspend fun getTemporaryBasal(radio: MinimedRadio): TempBasalPair? {
        return sendAndGetResponseWithCheck(radio, MedtronicCommandType.ReadTemporaryBasal) { _, _, rawContent ->
            if (rawContent.size >= 5) TempBasalPair(aapsLogger, rawContent)
            else {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Received invalid TempBasal response" + ByteUtil.getHex(rawContent))
                null
            }
        }
    }

    suspend fun getPumpSettings(radio: MinimedRadio): Map<String, PumpSettingDTO>? {
        return sendAndGetResponseWithCheck(radio, getSettings(medtronicUtil.medtronicPumpModel)) { _, _, rawContent ->
            medtronicConverter.decodeSettingsLoop(rawContent)
        }
    }

    suspend fun setBolus(radio: MinimedRadio, units: Double): Boolean {
        aapsLogger.info(LTag.PUMPCOMM, "setBolus: $units")
        return setCommand(radio, MedtronicCommandType.SetBolus, medtronicUtil.getBolusStrokes(units))
    }

    suspend fun setTemporaryBasal(radio: MinimedRadio, tbr: TempBasalPair): Boolean {
        aapsLogger.info(LTag.PUMPCOMM, "setTBR: " + tbr.description)
        return setCommand(radio, MedtronicCommandType.SetTemporaryBasal, tbr.asRawData)
    }

    suspend fun setPumpTime(radio: MinimedRadio): Boolean {
        val gc = GregorianCalendar()
        gc.add(Calendar.SECOND, 5)
        aapsLogger.info(LTag.PUMPCOMM, "setPumpTime: " + DateTimeUtil.toString(gc))
        val yearByte = getByteArrayFromUnsignedShort(gc[Calendar.YEAR], true)
        val timeData = byteArrayOf(
            7,
            gc[Calendar.HOUR_OF_DAY].toByte(),
            gc[Calendar.MINUTE].toByte(),
            gc[Calendar.SECOND].toByte(),
            yearByte[0],
            yearByte[1],
            (gc[Calendar.MONTH] + 1).toByte(),
            gc[Calendar.DAY_OF_MONTH].toByte()
        )
        return setCommand(radio, MedtronicCommandType.SetRealTimeClock, timeData)
    }

    private suspend fun setCommand(radio: MinimedRadio, commandType: MedtronicCommandType, body: ByteArray): Boolean {
        for (retries in 0..MAX_COMMAND_TRIES) {
            try {
                if (doWakeUpBeforeCommand) wakeUp(radio)
                if (debugSetCommands) aapsLogger.debug(
                    LTag.PUMPCOMM,
                    String.format(Locale.ENGLISH, "%s: Body - %s", commandType.commandDescription, ByteUtil.getHex(body))
                )
                val msg = makePumpMessage(commandType, CarelinkLongMessageBody(body))
                val pumpMessage = runCommandWithArgs(radio, msg)
                if (debugSetCommands) aapsLogger.debug(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "%s: %s", commandType.commandDescription, pumpMessage.responseContent))
                if (pumpMessage.commandType === MedtronicCommandType.CommandACK) {
                    return true
                } else {
                    aapsLogger.warn(LTag.PUMPCOMM, "We received non-ACK response from pump: " + pumpMessage.responseContent)
                }
            } catch (e: MedtronicCommException) {
                aapsLogger.warn(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Error getting response from pump (error=%s, retry=%d)", e.message, retries + 1))
            }
        }
        return false
    }

    suspend fun cancelTBR(radio: MinimedRadio): Boolean {
        return setTemporaryBasal(radio, TempBasalPair(0.0, false, 0))
    }

    suspend fun getRemainingBattery(radio: MinimedRadio): BatteryStatusDTO? {
        return sendAndGetResponseWithCheck(radio, MedtronicCommandType.GetBatteryStatus) { _, _, rawContent ->
            medtronicConverter.decodeBatteryStatus(rawContent)
        }
    }

    suspend fun setBasalProfile(radio: MinimedRadio, basalProfile: BasalProfile): Boolean {
        val basalProfileFrames = medtronicUtil.getBasalProfileFrames(basalProfile.rawData)
        for (retries in 0..MAX_COMMAND_TRIES) {
            var responseMessage: PumpMessage? = null
            try {
                responseMessage = runCommandWithFrames(radio, MedtronicCommandType.SetBasalProfileSTD, basalProfileFrames)
                if (responseMessage!!.commandType === MedtronicCommandType.CommandACK) return true
            } catch (e: MedtronicCommException) {
                aapsLogger.warn(LTag.PUMPCOMM, String.format(Locale.ENGLISH, "Error getting response from pump (error=%s, retry=%d)", e.message, retries + 1))
            }
            if (responseMessage != null) aapsLogger.warn(
                LTag.PUMPCOMM,
                String.format(
                    Locale.ENGLISH,
                    "Set Basal Profile: Invalid response: commandType=%s,rawData=%s",
                    responseMessage.commandType,
                    ByteUtil.shortHexString(responseMessage.rawContent)
                )
            ) else aapsLogger.warn(
                LTag.PUMPCOMM, "Set Basal Profile: Null response."
            )
        }
        return false
    }
}
