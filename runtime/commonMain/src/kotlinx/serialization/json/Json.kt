/*
 * Copyright 2017-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
package kotlinx.serialization.json

import kotlinx.serialization.*
import kotlinx.serialization.json.internal.*
import kotlinx.serialization.modules.*
import kotlin.jvm.*
import kotlin.native.concurrent.*
import kotlin.reflect.*

/**
 * The main entry point to work with JSON serialization.
 * It is typically used by constructing an application-specific instance, with configured json-specific behaviour
 * ([configuration] constructor parameter) and, if necessary, registered
 * custom serializers (in [SerialModule] provided by [context] constructor parameter).
 * Then constructed instance can be used either as regular [SerialFormat] or [StringFormat]
 * or for converting objects to [JsonElement] back and forth.
 *
 * This is the only serial format which has first-class [JsonElement] support.
 * Any serializable class can be serialized to or from [JsonElement] with [Json.decodeFromJsonElement] and [Json.encodeToJsonElement] respectively or
 * serialize properties of [JsonElement] type.
 *
 * Example of usage:
 * ```
 * @Serializable
 * class DataHolder(val id: Int, val data: String, val extensions: JsonElement)
 *
 * val json = Json(JsonConfiguration.Default)
 * val instance = DataHolder(42, "some data", json { "additional key" to "value" })
 *
 * // Plain StringFormat usage
 * val stringOutput: String = json.encodeToString(instance)
 *
 * // JsonElement serialization specific for JSON only
 * val jsonTree: JsonElement = json.encodeToJsonElement(instance)
 *
 * // Deserialize from string
 * val deserialized: DataHolder = json.decodeFromString<DataHolder>(stringOutput)
 *
 * // Deserialize from json tree, JSON-specific
 * val deserializedFromTree: DataHolder = json.decodeFromJsonElement<DataHolder>(jsonTree)
 *
 *  // Deserialize from string to JSON tree, Json-specific
 *  val deserializedToTree: JsonElement = json.parseJsonElement(stringOutput)
 * ```
 */
public class Json

/**
 * Default Json constructor not marked as unstable API.
 * To configure Json format behavior while still using only stable API it is possible to use `JsonConfiguration.copy` factory:
 * ```
 * val json = Json(configuration: = JsonConfiguration.Stable.copy(prettyPrint = true))
 * ```
 */
public constructor(
    @JvmField internal val configuration: JsonConfiguration = JsonConfiguration.Stable,
    context: SerialModule = EmptyModule
) : StringFormat {
    override val context: SerialModule = context + defaultJsonModule

    /**
     * DSL-like constructor for Json.
     * This constructor is marked with unstable default: its default parameters values and behaviour may change in the next releases.
     */
    @UnstableDefault
    public constructor(block: JsonBuilder.() -> Unit) : this(JsonBuilder().apply { block() })

    @OptIn(UnstableDefault::class)
    @Deprecated(
        message = "Default constructor is deprecated, please specify the desired configuration explicitly or use Json(JsonConfiguration.Default)",
        replaceWith = ReplaceWith("Json(JsonConfiguration.Default)"),
        level = DeprecationLevel.ERROR
    )
    public constructor() : this(JsonConfiguration(useArrayPolymorphism = true))

    @OptIn(UnstableDefault::class)
    private constructor(builder: JsonBuilder) : this(builder.buildConfiguration(), builder.buildModule())

    init {
        validateConfiguration()
    }

    /**
     * Serializes [value] into an equivalent JSON using provided [serializer].
     * @throws [JsonException] if given value can not be encoded
     * @throws [SerializationException] if given value can not be serialized
     */
    public override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
        val result = StringBuilder()
        val encoder = StreamingJsonEncoder(
            result, this,
            WriteMode.OBJ,
            arrayOfNulls(WriteMode.values().size)
        )
        encoder.encode(serializer, value)
        return result.toString()
    }

    /**
     * Serializes [value] into an equivalent [JsonElement] using provided [serializer].
     * @throws [JsonException] if given value can not be encoded
     * @throws [SerializationException] if given value can not be serialized
     */
    public fun <T> encodeToJsonElement(serializer: SerializationStrategy<T>, value: T): JsonElement {
        return writeJson(value, serializer)
    }

    /**
     * Serializes [value] into an equivalent [JsonElement] using serializer registered in the module.
     * @throws [JsonException] if given value can not be encoded
     * @throws [SerializationException] if given value can not be serialized
     */
    public inline fun <reified T : Any> encodeToJsonElement(value: T): JsonElement {
        return encodeToJsonElement(context.getContextualOrDefault(), value)
    }

    /**
     * Deserializes given json [string] into a corresponding object of type [T] using provided [deserializer].
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        val reader = JsonReader(string)
        val input = StreamingJsonDecoder(this, WriteMode.OBJ, reader)
        val result = input.decode(deserializer)
        if (!reader.isDone) { error("Reader has not consumed the whole input: $reader") }
        return result
    }

    /**
     * Deserializes given json [string] into a corresponding [JsonElement] representation.
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public fun parseToJsonElement(string: String): JsonElement {
        return decodeFromString(JsonElementSerializer, string)
    }

    /**
     * Deserializes [json] element into a corresponding object of type [T] using provided [deserializer].
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public fun <T> decodeFromJsonElement(deserializer: DeserializationStrategy<T>, json: JsonElement): T {
        return readJson(json, deserializer)
    }

    /**
     * Deserializes [element] element into a corresponding object of type [T] using serializer registered in the module.
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public inline fun <reified T : Any> decodeFromJsonElement(element: JsonElement): T =
        decodeFromJsonElement(context.getContextualOrDefault(), element)

    /**
     * The default instance of [Json] in the form of companion object. Configured with [JsonConfiguration.Default].
     */
    @UnstableDefault
    public companion object Default : StringFormat {

        private val jsonInstance = Json(JsonConfiguration.Default)

        override val context: SerialModule
            get() = jsonInstance.context

        override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String =
            jsonInstance.encodeToString(serializer, value)

        override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T =
            jsonInstance.decodeFromString(deserializer, string)

        /**
         * @see Json.encodeToJsonElement
         */
        public fun <T> encodeToJsonElement(serializer: SerializationStrategy<T>, value: T): JsonElement {
            return jsonInstance.writeJson(value, serializer)
        }

        /**
         * @see Json.encodeToJsonElement
         */
        public inline fun <reified T : Any> encodeToJsonElement(value: T): JsonElement {
            return encodeToJsonElement(context.getContextualOrDefault(), value)
        }

        /**
         * @see Json.parseToJsonElement
         */
        public fun parseToJsonElement(string: String): JsonElement {
            return decodeFromString(JsonElementSerializer, string)
        }

        /**
         * @see Json.decodeFromJsonElement
         */
        public fun <T> decodeFromJsonElement(deserializer: DeserializationStrategy<T>, json: JsonElement): T {
            return jsonInstance.readJson(json, deserializer)
        }

        /**
         * @see Json.decodeFromJsonElement
         */
        public inline fun <reified T : Any> decodeFromJsonElement(tree: JsonElement): T =
            decodeFromJsonElement(context.getContextualOrDefault(), tree)
    }

    private fun validateConfiguration() {
        if (configuration.useArrayPolymorphism) return
        val collector = ContextValidator(configuration.classDiscriminator)
        context.dumpTo(collector)
    }
}

/**
 * Builder to conveniently build Json instances.
 * Properties of this builder are directly matched with properties of [JsonConfiguration].
 */
@UnstableDefault
@Suppress("unused")
public class JsonBuilder {
    public var encodeDefaults: Boolean = true
    @Deprecated(level = DeprecationLevel.ERROR,
        message = "'strictMode = true' is replaced with 3 new configuration parameters: " +
                "'ignoreUnknownKeys = false' to fail if an unknown key is encountered, " +
                "'serializeSpecialFloatingPointValues = false' to fail on 'NaN' and 'Infinity' values, " +
                "'isLenient = false' to prohibit parsing of any non-compliant or malformed JSON")
    public var strictMode: Boolean = true
    public var ignoreUnknownKeys: Boolean = false
    public var isLenient: Boolean = false
    public var serializeSpecialFloatingPointValues: Boolean = false
    @Deprecated(level = DeprecationLevel.ERROR,
        message = "'unquoted' is deprecated in the favour of 'unquotedPrint'",
        replaceWith = ReplaceWith("unquotedPrint"))
    public var unquoted: Boolean = false
    public var allowStructuredMapKeys: Boolean = false
    public var prettyPrint: Boolean = false
    public var unquotedPrint: Boolean = false
    public var indent: String = "    "
    public var coerceInputValues: Boolean = false
    public var useArrayPolymorphism: Boolean = false
    public var classDiscriminator: String = "type"
    public var serialModule: SerialModule = EmptyModule

    public fun buildConfiguration(): JsonConfiguration =
        JsonConfiguration(
            encodeDefaults,
            ignoreUnknownKeys,
            isLenient,
            serializeSpecialFloatingPointValues,
            allowStructuredMapKeys,
            prettyPrint,
            unquotedPrint,
            indent,
            coerceInputValues,
            useArrayPolymorphism,
            classDiscriminator
        )

    public fun buildModule(): SerialModule = serialModule
}

@SharedImmutable
private val defaultJsonModule = serializersModuleOf(
    mapOf<KClass<*>, KSerializer<*>>(
        JsonElement::class to JsonElementSerializer,
        JsonPrimitive::class to JsonPrimitiveSerializer,
        JsonLiteral::class to JsonLiteralSerializer,
        JsonNull::class to JsonNullSerializer,
        JsonObject::class to JsonObjectSerializer,
        JsonArray::class to JsonArraySerializer
    )
)

internal const val lenientHint = "Use 'JsonConfiguration.isLenient = true' to accept non-compliant JSON"
