@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.Extensions
import com.maneesh.universalai.connector.contract.json.CURRENT_CONTRACT_VERSION
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A raw-backed, forward-compatible canonical capability name. */
@JvmInline
@Serializable(with = UniversalAiCapabilityNameSerializer::class)
@HiddenFromObjC
value class UniversalAiCapabilityName private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this == Streaming || this == StructuredOutput

    companion object {
        /** Support for the canonical stream-event operation, independent of P3 transport. */
        val Streaming: UniversalAiCapabilityName = UniversalAiCapabilityName("streaming")

        /** Support for governed JSON-schema intent and canonical structured JSON output. */
        val StructuredOutput: UniversalAiCapabilityName =
            UniversalAiCapabilityName("structured_output")

        fun of(rawValue: String): UniversalAiCapabilityName {
            contractRequire(
                condition = CAPABILITY_TOKEN_PATTERN.matches(rawValue),
                code = "invalid_capability_name",
                path = "",
            ) {
                "Capability names must be 1-$MAX_CAPABILITY_TOKEN_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiCapabilityName(rawValue)
        }
    }
}

/** The conservative semantic interpretation of a capability-support wire value. */
@HiddenFromObjC
enum class UniversalAiCapabilitySupportState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * A raw-backed capability-support value.
 *
 * Future raw values are retained exactly and interpreted conservatively as [UniversalAiCapabilitySupportState.UNKNOWN].
 */
@JvmInline
@Serializable(with = UniversalAiCapabilitySupportSerializer::class)
@HiddenFromObjC
value class UniversalAiCapabilitySupport private constructor(
    val rawValue: String,
) {
    val semanticState: UniversalAiCapabilitySupportState
        get() =
            when (this) {
                Supported -> UniversalAiCapabilitySupportState.SUPPORTED
                Unsupported -> UniversalAiCapabilitySupportState.UNSUPPORTED
                else -> UniversalAiCapabilitySupportState.UNKNOWN
            }

    val isKnown: Boolean
        get() = this == Supported || this == Unsupported || this == Unknown

    companion object {
        val Supported: UniversalAiCapabilitySupport = UniversalAiCapabilitySupport("supported")
        val Unsupported: UniversalAiCapabilitySupport =
            UniversalAiCapabilitySupport("unsupported")
        val Unknown: UniversalAiCapabilitySupport = UniversalAiCapabilitySupport("unknown")

        fun of(rawValue: String): UniversalAiCapabilitySupport {
            contractRequire(
                condition = CAPABILITY_TOKEN_PATTERN.matches(rawValue),
                code = "invalid_capability_support",
                path = "/support",
            ) {
                "Capability support values must be 1-$MAX_CAPABILITY_TOKEN_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiCapabilitySupport(rawValue)
        }
    }
}

/** A raw-backed, forward-compatible canonical capability-limit name. */
@JvmInline
@Serializable(with = UniversalAiCapabilityLimitNameSerializer::class)
@HiddenFromObjC
value class UniversalAiCapabilityLimitName private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this == MaxSchemaBytes || this == MaxSchemaDepth

    companion object {
        /** Maximum compact UTF-8 bytes accepted for a governed structured-output schema. */
        val MaxSchemaBytes: UniversalAiCapabilityLimitName =
            UniversalAiCapabilityLimitName("max_schema_bytes")

        /** Maximum governed structured-output schema container depth. */
        val MaxSchemaDepth: UniversalAiCapabilityLimitName =
            UniversalAiCapabilityLimitName("max_schema_depth")

        fun of(rawValue: String): UniversalAiCapabilityLimitName {
            contractRequire(
                condition = CAPABILITY_TOKEN_PATTERN.matches(rawValue),
                code = "invalid_capability_limit_name",
                path = "",
            ) {
                "Capability-limit names must be 1-$MAX_CAPABILITY_TOKEN_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiCapabilityLimitName(rawValue)
        }
    }
}

/** One immutable capability declaration with optional canonical limits and governed extensions. */
@Serializable(with = UniversalAiCapabilityDeclarationSerializer::class)
@HiddenFromObjC
class UniversalAiCapabilityDeclaration(
    val support: UniversalAiCapabilitySupport,
    limits: Map<UniversalAiCapabilityLimitName, Long> = emptyMap(),
    val extensions: Extensions = Extensions.Empty,
) {
    private val storedLimits = limits.toMap()

    /** Returns a defensive snapshot whose raw keys retain unknown future limit names. */
    val limits: Map<UniversalAiCapabilityLimitName, Long>
        get() = storedLimits.toMap()

    init {
        contractRequire(
            condition = storedLimits.size <= MAX_CAPABILITY_LIMITS,
            code = "capability_limit_count_exceeded",
            path = "/limits",
        ) {
            "Capability declarations must not contain more than $MAX_CAPABILITY_LIMITS limits."
        }
        storedLimits.forEach { (name, value) ->
            val path = "/limits/${name.rawValue.escapeCapabilityPointerToken()}"
            contractRequire(
                condition = value in 0..MAX_JSON_SAFE_INTEGER,
                code = "capability_limit_out_of_range",
                path = path,
            ) {
                "Capability limits must be non-negative JSON safe integers."
            }
            if (name == UniversalAiCapabilityLimitName.MaxSchemaBytes ||
                name == UniversalAiCapabilityLimitName.MaxSchemaDepth
            ) {
                contractRequire(
                    condition = value > 0,
                    code = "capability_limit_out_of_range",
                    path = path,
                ) {
                    "${name.rawValue} must be greater than zero."
                }
            }
        }
        contractRequire(
            condition =
                support != UniversalAiCapabilitySupport.Unsupported || storedLimits.isEmpty(),
            code = "unsupported_capability_limits",
            path = "/limits",
        ) {
            "Unsupported capabilities must not declare limits."
        }
    }

    internal fun limitsForSerialization(): Map<UniversalAiCapabilityLimitName, Long> = storedLimits

    override fun equals(other: Any?): Boolean =
        other is UniversalAiCapabilityDeclaration &&
            support == other.support &&
            storedLimits == other.storedLimits &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = support.hashCode()
        result = 31 * result + storedLimits.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }
}

/**
 * An immutable set of raw-keyed capability declarations.
 *
 * An absent declaration and an explicitly declared `unknown` value are distinguishable through
 * [get], while [supportState] conservatively reports both as unknown.
 */
@Serializable(with = UniversalAiCapabilitySetSerializer::class)
@HiddenFromObjC
class UniversalAiCapabilitySet private constructor(
    declarations: Map<UniversalAiCapabilityName, UniversalAiCapabilityDeclaration>,
) {
    private val storedDeclarations = declarations.toMap()

    val isEmpty: Boolean
        get() = storedDeclarations.isEmpty()

    val size: Int
        get() = storedDeclarations.size

    /** Returns a defensive snapshot containing known and future capability declarations. */
    val declarations: Map<UniversalAiCapabilityName, UniversalAiCapabilityDeclaration>
        get() = storedDeclarations.toMap()

    operator fun get(name: UniversalAiCapabilityName): UniversalAiCapabilityDeclaration? =
        storedDeclarations[name]

    fun supportState(name: UniversalAiCapabilityName): UniversalAiCapabilitySupportState =
        storedDeclarations[name]?.support?.semanticState
            ?: UniversalAiCapabilitySupportState.UNKNOWN

    internal fun declarationsForSerialization():
        Map<UniversalAiCapabilityName, UniversalAiCapabilityDeclaration> = storedDeclarations

    override fun equals(other: Any?): Boolean =
        other is UniversalAiCapabilitySet && storedDeclarations == other.storedDeclarations

    override fun hashCode(): Int = storedDeclarations.hashCode()

    companion object {
        val Empty: UniversalAiCapabilitySet = UniversalAiCapabilitySet(emptyMap())

        fun of(
            declarations: Map<UniversalAiCapabilityName, UniversalAiCapabilityDeclaration>,
        ): UniversalAiCapabilitySet {
            if (declarations.isEmpty()) {
                return Empty
            }
            contractRequire(
                condition = declarations.size <= MAX_CAPABILITIES,
                code = "capability_count_exceeded",
                path = "",
            ) {
                "Capability sets must not contain more than $MAX_CAPABILITIES declarations."
            }
            declarations.entries
                .sortedBy { entry -> entry.key.rawValue }
                .forEach { (capability, declaration) ->
                    declaration.limitsForSerialization().keys
                        .sortedBy { limit -> limit.rawValue }
                        .forEach { limit ->
                            if (
                                limit.isStructuredOutputLimit &&
                                capability != UniversalAiCapabilityName.StructuredOutput
                            ) {
                                contractRequire(
                                    condition = false,
                                    code = "capability_limit_not_applicable",
                                    path =
                                        "/${capability.rawValue.escapeCapabilityPointerToken()}" +
                                            "/limits/" +
                                            limit.rawValue.escapeCapabilityPointerToken(),
                                ) {
                                    "${limit.rawValue} applies only to the " +
                                        "structured_output capability."
                                }
                            }
                        }
                }
            val stableDeclarations =
                declarations.entries
                    .sortedBy { entry -> entry.key.rawValue }
                    .associate { entry -> entry.key to entry.value }
            return UniversalAiCapabilitySet(stableDeclarations)
        }

        fun of(
            vararg declarations:
                Pair<UniversalAiCapabilityName, UniversalAiCapabilityDeclaration>,
        ): UniversalAiCapabilitySet {
            contractRequire(
                condition =
                    declarations.map { declaration -> declaration.first }.distinct().size ==
                        declarations.size,
                code = "duplicate_capability",
                path = "",
            ) {
                "Capability sets must not contain duplicate names."
            }
            return of(declarations.toMap())
        }

        /**
         * Materializes effective model capabilities from provider defaults and sparse model
         * declarations. A model declaration replaces the complete same-name provider entry.
         */
        fun resolve(
            providerProfile: UniversalAiProviderCapabilityProfile,
            modelTarget: UniversalAiTarget,
            modelOverrides: UniversalAiCapabilitySet = Empty,
        ): UniversalAiCapabilitySet {
            contractRequire(
                condition = providerProfile.providerId == modelTarget.providerId,
                code = "capability_provider_mismatch",
                path = "/target/providerId",
            ) {
                "Provider capability defaults must match the model target provider."
            }
            if (modelOverrides.isEmpty) {
                return providerProfile.capabilities
            }
            val resolved =
                providerProfile.capabilities
                    .declarationsForSerialization()
                    .toMutableMap()
            resolved.putAll(modelOverrides.declarationsForSerialization())
            return of(resolved)
        }
    }
}

/** Serializable provider-level capability defaults without discovery behavior. */
@Serializable(with = UniversalAiProviderCapabilityProfileSerializer::class)
@HiddenFromObjC
class UniversalAiProviderCapabilityProfile(
    val providerId: ProviderId,
    val capabilities: UniversalAiCapabilitySet = UniversalAiCapabilitySet.Empty,
    val extensions: Extensions = Extensions.Empty,
) {
    val contractVersion: String
        get() = CURRENT_CONTRACT_VERSION

    fun toJson(): String =
        CanonicalJson.encode(
            UniversalAiProviderCapabilityProfile.serializer(),
            this,
        )

    override fun equals(other: Any?): Boolean =
        other is UniversalAiProviderCapabilityProfile &&
            providerId == other.providerId &&
            capabilities == other.capabilities &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }

    companion object {
        fun fromJson(json: String): UniversalAiProviderCapabilityProfile =
            CanonicalJson.decode(
                UniversalAiProviderCapabilityProfile.serializer(),
                json,
            )
    }
}

private const val MAX_CAPABILITY_TOKEN_CHARACTERS: Int = 64
private const val MAX_CAPABILITIES: Int = 64
private const val MAX_CAPABILITY_LIMITS: Int = 64

private val CAPABILITY_TOKEN_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")

private val UniversalAiCapabilityLimitName.isStructuredOutputLimit: Boolean
    get() = this == UniversalAiCapabilityLimitName.MaxSchemaBytes ||
        this == UniversalAiCapabilityLimitName.MaxSchemaDepth

internal object UniversalAiCapabilityNameSerializer :
    ValidatedStringSerializer<UniversalAiCapabilityName>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiCapabilityName",
        create = UniversalAiCapabilityName::of,
        rawValue = UniversalAiCapabilityName::rawValue,
    )

internal object UniversalAiCapabilitySupportSerializer :
    ValidatedStringSerializer<UniversalAiCapabilitySupport>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiCapabilitySupport",
        create = UniversalAiCapabilitySupport::of,
        rawValue = UniversalAiCapabilitySupport::rawValue,
    )

internal object UniversalAiCapabilityLimitNameSerializer :
    ValidatedStringSerializer<UniversalAiCapabilityLimitName>(
        serialName = "com.maneesh.universalai.connector.contract.UniversalAiCapabilityLimitName",
        create = UniversalAiCapabilityLimitName::of,
        rawValue = UniversalAiCapabilityLimitName::rawValue,
    )

internal object UniversalAiCapabilityDeclarationSerializer :
    KSerializer<UniversalAiCapabilityDeclaration> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiCapabilityDeclaration,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiCapabilityDeclaration supports JSON encoding only.",
                )
        val members = linkedMapOf<String, JsonElement>()
        members["support"] = JsonPrimitive(value.support.rawValue)
        if (value.limitsForSerialization().isNotEmpty()) {
            members["limits"] =
                JsonObject(
                    value.limitsForSerialization().entries
                        .sortedBy { entry -> entry.key.rawValue }
                        .associate { entry ->
                            entry.key.rawValue to JsonPrimitive(entry.value)
                        },
                )
        }
        if (!value.extensions.isEmpty) {
            members["extensions"] =
                CanonicalJson.format.encodeToJsonElement(
                    Extensions.serializer(),
                    value.extensions,
                )
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiCapabilityDeclaration {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiCapabilityDeclaration supports JSON decoding only.",
                )
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException(
                    "Expected an object for UniversalAiCapabilityDeclaration.",
                )

        return decodeCapabilityDeclaration {
            UniversalAiCapabilityDeclaration(
                support =
                    UniversalAiCapabilitySupport.of(
                        document.requiredCapabilityString("support"),
                    ),
                limits = document.optionalCapabilityLimits(),
                extensions =
                    document.optionalCapabilityNonNull("extensions")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/extensions") {
                            CanonicalJson.format.decodeFromJsonElement(
                                Extensions.serializer(),
                                element,
                            )
                        }
                    } ?: Extensions.Empty,
            )
        }
    }
}

internal object UniversalAiCapabilitySetSerializer : KSerializer<UniversalAiCapabilitySet> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiCapabilitySet,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("UniversalAiCapabilitySet supports JSON encoding only.")
        jsonEncoder.encodeJsonElement(
            JsonObject(
                value.declarationsForSerialization().entries
                    .sortedBy { entry -> entry.key.rawValue }
                    .associate { entry ->
                        entry.key.rawValue to
                            CanonicalJson.format.encodeToJsonElement(
                                UniversalAiCapabilityDeclaration.serializer(),
                                entry.value,
                            )
                    },
            ),
        )
    }

    override fun deserialize(decoder: Decoder): UniversalAiCapabilitySet {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("UniversalAiCapabilitySet supports JSON decoding only.")
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Expected an object for UniversalAiCapabilitySet.")

        return decodeCapabilitySet {
            UniversalAiCapabilitySet.of(
                document.map { (rawName, element) ->
                    val path = "/${rawName.escapeCapabilityPointerToken()}"
                    val name =
                        decodeSemanticComponent(pathPrefix = path) {
                            UniversalAiCapabilityName.of(rawName)
                        }
                    val declaration =
                        decodeSemanticComponent(pathPrefix = path) {
                            CanonicalJson.format.decodeFromJsonElement(
                                UniversalAiCapabilityDeclaration.serializer(),
                                element,
                            )
                        }
                    name to declaration
                }.toMap(),
            )
        }
    }
}

internal object UniversalAiProviderCapabilityProfileSerializer :
    KSerializer<UniversalAiProviderCapabilityProfile> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: UniversalAiProviderCapabilityProfile,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException(
                    "UniversalAiProviderCapabilityProfile supports JSON encoding only.",
                )
        val members = linkedMapOf<String, JsonElement>()
        members["contractVersion"] = JsonPrimitive(CURRENT_CONTRACT_VERSION)
        members["providerId"] = JsonPrimitive(value.providerId.rawValue)
        members["capabilities"] =
            CanonicalJson.format.encodeToJsonElement(
                UniversalAiCapabilitySet.serializer(),
                value.capabilities,
            )
        if (!value.extensions.isEmpty) {
            members["extensions"] =
                CanonicalJson.format.encodeToJsonElement(
                    Extensions.serializer(),
                    value.extensions,
                )
        }
        jsonEncoder.encodeJsonElement(JsonObject(members))
    }

    override fun deserialize(decoder: Decoder): UniversalAiProviderCapabilityProfile {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException(
                    "UniversalAiProviderCapabilityProfile supports JSON decoding only.",
                )
        val document =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException(
                    "Expected an object for UniversalAiProviderCapabilityProfile.",
                )
        val contractVersion = document.requiredCapabilityString("contractVersion")
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw semanticSerializationException(
                code = "unsupported_contract_version",
                path = "/contractVersion",
                message = "Unsupported contractVersion '$contractVersion'.",
            )
        }

        return decodeProviderCapabilityProfile {
            UniversalAiProviderCapabilityProfile(
                providerId =
                    ProviderId.of(
                        document.requiredCapabilityString("providerId"),
                    ),
                capabilities =
                    decodeSemanticComponent(pathPrefix = "/capabilities") {
                        CanonicalJson.format.decodeFromJsonElement(
                            UniversalAiCapabilitySet.serializer(),
                            document.requiredCapabilityNonNull("capabilities"),
                        )
                    },
                extensions =
                    document.optionalCapabilityNonNull("extensions")?.let { element ->
                        decodeSemanticComponent(pathPrefix = "/extensions") {
                            CanonicalJson.format.decodeFromJsonElement(
                                Extensions.serializer(),
                                element,
                            )
                        }
                    } ?: Extensions.Empty,
            )
        }
    }
}

private fun JsonObject.requiredCapabilityNonNull(name: String): JsonElement =
    this[name]
        ?.takeUnless { value -> value === JsonNull }
        ?: throw SerializationException("Missing required non-null '$name'.")

private fun JsonObject.optionalCapabilityNonNull(name: String): JsonElement? {
    val value = this[name] ?: return null
    if (value === JsonNull) {
        throw SerializationException("'$name' must not be null.")
    }
    return value
}

private fun JsonObject.requiredCapabilityString(name: String): String {
    val value = requiredCapabilityNonNull(name)
    if (value !is JsonPrimitive || !value.isString) {
        throw SerializationException("'$name' must be a string.")
    }
    return value.content
}

private fun JsonObject.optionalCapabilityLimits():
    Map<UniversalAiCapabilityLimitName, Long> {
    val value = optionalCapabilityNonNull("limits") ?: return emptyMap()
    val limits =
        value as? JsonObject
            ?: throw SerializationException("'limits' must be an object.")
    return limits.map { (rawName, element) ->
        val path = "/limits/${rawName.escapeCapabilityPointerToken()}"
        val name =
            decodeSemanticComponent(pathPrefix = path) {
                UniversalAiCapabilityLimitName.of(rawName)
            }
        name to element.requiredCapabilitySafeInteger(path)
    }.toMap()
}

private fun JsonElement.requiredCapabilitySafeInteger(path: String): Long {
    val primitive =
        this as? JsonPrimitive
            ?: throw SerializationException("'$path' must be an integer.")
    if (
        primitive === JsonNull ||
        primitive.isString ||
        primitive.booleanOrNull != null ||
        !JsonNumberSemantics.isMathematicalInteger(primitive.content)
    ) {
        throw SerializationException("'$path' must be an integer.")
    }
    return primitive.content.toCapabilitySafeIntegerOrNull()
        ?: throw semanticSerializationException(
            code = "capability_limit_out_of_range",
            path = path,
            message = "Capability limits must be non-negative JSON safe integers.",
        )
}

private fun String.toCapabilitySafeIntegerOrNull(): Long? {
    if (
        JsonNumberSemantics.compare(this, "0")?.let { comparison -> comparison >= 0 } != true ||
        JsonNumberSemantics.compare(this, MAX_JSON_SAFE_INTEGER.toString())
            ?.let { comparison -> comparison <= 0 } != true
    ) {
        return null
    }
    val normalized = JsonNumberSemantics.normalize(this) ?: return null
    if (normalized == "0") {
        return 0L
    }
    val exponentMarker = normalized.lastIndexOf('e')
    if (exponentMarker <= 0 || exponentMarker == normalized.lastIndex) {
        return null
    }
    val digits = normalized.substring(0, exponentMarker)
    if (digits.startsWith('-')) {
        return null
    }
    val exponent = normalized.substring(exponentMarker + 1).toIntOrNull() ?: return null
    if (exponent < 0) {
        return null
    }
    return buildString(digits.length + exponent) {
        append(digits)
        repeat(exponent) {
            append('0')
        }
    }.toLongOrNull()
}

private fun String.escapeCapabilityPointerToken(): String =
    replace("~", "~0").replace("/", "~1")

private inline fun <T> decodeCapabilityDeclaration(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiCapabilityDeclaration.",
            cause = failure,
        )
    }

private inline fun <T> decodeCapabilitySet(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiCapabilitySet.",
            cause = failure,
        )
    }

private inline fun <T> decodeProviderCapabilityProfile(block: () -> T): T =
    try {
        block()
    } catch (failure: SerializationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw SerializationException(
            message = failure.message ?: "Invalid UniversalAiProviderCapabilityProfile.",
            cause = failure,
        )
    }
