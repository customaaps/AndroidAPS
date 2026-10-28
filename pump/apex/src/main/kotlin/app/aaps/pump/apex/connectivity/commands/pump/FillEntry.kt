package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.core.utils.toHex

/**
 * One record of the pump's fill history: a cannula fill or a tubing fill.
 *
 * DELIBERATELY DECODES NOTHING. The whole product of this object is the raw payload in the log, and
 * that is the point: no frame of this value id has ever been captured from a real pump, so any field
 * this class named would be a guess printed as a fact. The maker's own app reads a 6-byte BCD date,
 * a two-byte amount in 0.025 U steps and a one-byte kind, but the byte offsets past the date come
 * from a decompiler's register numbering, not from readable code, and a wrong offset would print a
 * plausible-looking insulin volume. Decode this only against a log that shows the real bytes.
 *
 * [validate] therefore always passes: this is a diagnostic, and a diagnostic that rejects the answer
 * it asked for reports the opposite of the truth - the reader would be released as "no answer" while
 * the pump was in fact talking.
 */
class FillEntry(val command: PumpCommand) : PumpObjectModel() {

    override fun validate(): String? = null

    override fun toString(): String = "FillEntry(raw=${command.objectData.toHex()})"
}
