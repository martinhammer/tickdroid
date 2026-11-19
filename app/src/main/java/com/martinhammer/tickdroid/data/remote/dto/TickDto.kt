package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One tick. [value] is kept as the raw JSON element: a Number's `10.4` can't be told from a
 * marker without knowing the track's type, so the pull converts it per type (`SyncManager`):
 * yes/no, counter and choice as an integer, a Number to thousandths through `numberFromWire`
 * (from the number's own text, never a Double), and a type this Tickdroid doesn't know through
 * [lenientTickValue].
 */
@Serializable
data class TickDto(
    val id: Long,
    val trackId: Long,
    val date: String,
    val value: JsonElement = JsonNull,
) {
    /** The value read leniently ([lenientTickValue]): what yes/no, counter and unknown types use. */
    val lenientValue: Int get() = lenientTickValue(value)
}

/**
 * A tick's value, read leniently. Yes/no and counter ticks are integers and come through as they
 * are. A track of a type this Tickdroid doesn't know may hold anything (a decimal, a choice):
 * that becomes 1 when it is non-zero or non-empty and 0 otherwise, since such a track's cells only
 * show "there is a value". One odd value must never fail the whole ticks read (and with it the
 * pull). The 1 is only a local marker: those tracks never push, so the server keeps the real value.
 */
internal fun lenientTickValue(element: JsonElement): Int = when (element) {
    JsonNull -> 0
    is JsonPrimitive -> when {
        element.isString -> if (element.content.isEmpty()) 0 else 1
        else -> element.intOrNull
            ?: element.booleanOrNull?.let { if (it) 1 else 0 }
            ?: element.doubleOrNull?.let { if (it == 0.0) 0 else 1 }
            ?: 1
    }
    is JsonObject, is JsonArray -> 1
}

/**
 * [lenientTickValue] as a serializer, for `set`'s yes/no answer ([TickYesNoResponse]).
 * `TickValueResponse` stays strict: a counter's answer is always an integer.
 */
internal object LenientTickValueSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientTickValue", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        return lenientTickValue(element)
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}
