@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.contractRequire
import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

/**
 * A provider-neutral immutable JSON value used inside a namespaced extension payload.
 *
 * This hierarchy deliberately does not expose kotlinx.serialization JSON DOM types.
 */
@Serializable(with = ExtensionValueSerializer::class)
@HiddenFromObjC
sealed class ExtensionValue {
    @HiddenFromObjC
    data object Null : ExtensionValue()

    @HiddenFromObjC
    class BooleanValue internal constructor(
        val value: Boolean,
    ) : ExtensionValue() {
        override fun equals(other: Any?): Boolean =
            other is BooleanValue && value == other.value

        override fun hashCode(): Int = value.hashCode()
    }

    @HiddenFromObjC
    class StringValue internal constructor(
        val value: String,
    ) : ExtensionValue() {
        override fun equals(other: Any?): Boolean =
            other is StringValue && value == other.value

        override fun hashCode(): Int = value.hashCode()
    }

    @HiddenFromObjC
    class NumberValue internal constructor(
        val value: ExtensionNumber,
    ) : ExtensionValue() {
        override fun equals(other: Any?): Boolean =
            other is NumberValue && value == other.value

        override fun hashCode(): Int = value.hashCode()
    }

    @HiddenFromObjC
    class ArrayValue internal constructor(
        values: List<ExtensionValue>,
    ) : ExtensionValue() {
        private val storedValues = values.toList()

        /** Returns a defensive snapshot of the ordered values. */
        val values: List<ExtensionValue>
            get() = storedValues.toList()

        internal fun valuesForSerialization(): List<ExtensionValue> = storedValues

        override fun equals(other: Any?): Boolean =
            other is ArrayValue && storedValues == other.storedValues

        override fun hashCode(): Int = storedValues.hashCode()
    }

    @HiddenFromObjC
    class ObjectValue internal constructor(
        members: Map<String, ExtensionValue>,
    ) : ExtensionValue() {
        private val storedMembers = members.toMap()

        /** Returns a defensive snapshot of the unordered members. */
        val members: Map<String, ExtensionValue>
            get() = storedMembers.toMap()

        operator fun get(name: String): ExtensionValue? = storedMembers[name]

        fun boolean(name: String): Boolean? =
            (storedMembers[name] as? BooleanValue)?.value

        fun string(name: String): String? =
            (storedMembers[name] as? StringValue)?.value

        fun number(name: String): ExtensionNumber? =
            (storedMembers[name] as? NumberValue)?.value

        fun array(name: String): ArrayValue? =
            storedMembers[name] as? ArrayValue

        fun objectValue(name: String): ObjectValue? =
            storedMembers[name] as? ObjectValue

        internal fun membersForSerialization(): Map<String, ExtensionValue> = storedMembers

        override fun equals(other: Any?): Boolean =
            other is ObjectValue && storedMembers == other.storedMembers

        override fun hashCode(): Int = storedMembers.hashCode()
    }

    companion object {
        fun boolean(value: Boolean): BooleanValue = BooleanValue(value)

        fun string(value: String): StringValue {
            requireValidExtensionString(value, path = "")
            return StringValue(value)
        }

        fun number(value: ExtensionNumber): NumberValue = NumberValue(value)

        fun number(rawValue: String): NumberValue = number(ExtensionNumber.of(rawValue))

        fun array(values: List<ExtensionValue>): ArrayValue {
            val result = ArrayValue(values)
            validateStandaloneExtensionValue(result)
            return result
        }

        fun array(vararg values: ExtensionValue): ArrayValue = array(values.toList())

        fun objectValue(members: Map<String, ExtensionValue>): ObjectValue {
            val result = ObjectValue(members)
            validateStandaloneExtensionValue(result)
            return result
        }

        fun objectValue(vararg members: Pair<String, ExtensionValue>): ObjectValue {
            val names = mutableSetOf<String>()
            val duplicateName =
                members.firstOrNull { member -> !names.add(member.first) }?.first
            contractRequire(
                condition = duplicateName == null,
                code = "duplicate_extension_member",
                path = duplicateName?.let { name -> extensionChildPath("", name) } ?: "",
            ) {
                "Extension objects must not contain duplicate member names."
            }
            return objectValue(members.toMap())
        }
    }
}

internal fun validateStandaloneExtensionValue(
    value: ExtensionValue,
    path: String = "",
) {
    ExtensionTreeValidator().validateRoot(value, path)
}

internal class ExtensionTreeValidator {
    private var nodeCount: Int = 0

    fun validateRoot(
        value: ExtensionValue,
        path: String,
    ) {
        validate(value, containerDepth = 1, path = path)
    }

    fun validatePayload(
        value: ExtensionValue.ObjectValue,
        path: String,
    ) {
        validate(value, containerDepth = 1, path = path)
    }

    private fun validate(
        value: ExtensionValue,
        containerDepth: Int,
        path: String,
    ) {
        nodeCount += 1
        contractRequire(
            condition = nodeCount <= ExtensionConstraints.MAX_VALUE_NODES,
            code = "extension_node_limit_exceeded",
            path = path,
        ) {
            "Extension payloads must not exceed ${ExtensionConstraints.MAX_VALUE_NODES} values."
        }

        when (value) {
            ExtensionValue.Null -> Unit
            is ExtensionValue.BooleanValue -> Unit
            is ExtensionValue.NumberValue -> Unit
            is ExtensionValue.StringValue -> requireValidExtensionString(value.value, path)
            is ExtensionValue.ArrayValue -> {
                contractRequire(
                    condition = containerDepth <= ExtensionConstraints.MAX_CONTAINER_DEPTH,
                    code = "extension_depth_limit_exceeded",
                    path = path,
                ) {
                    "Extension payloads must not exceed container depth " +
                        "${ExtensionConstraints.MAX_CONTAINER_DEPTH}."
                }
                val values = value.valuesForSerialization()
                contractRequire(
                    condition = values.size <= ExtensionConstraints.MAX_ARRAY_ELEMENTS,
                    code = "extension_array_element_limit_exceeded",
                    path = path,
                ) {
                    "Extension arrays must not exceed " +
                        "${ExtensionConstraints.MAX_ARRAY_ELEMENTS} elements."
                }
                values.forEachIndexed { index, child ->
                    validate(
                        value = child,
                        containerDepth =
                            if (child is ExtensionValue.ArrayValue ||
                                child is ExtensionValue.ObjectValue
                            ) {
                                containerDepth + 1
                            } else {
                                containerDepth
                            },
                        path = "$path/$index",
                    )
                }
            }

            is ExtensionValue.ObjectValue -> {
                contractRequire(
                    condition = containerDepth <= ExtensionConstraints.MAX_CONTAINER_DEPTH,
                    code = "extension_depth_limit_exceeded",
                    path = path,
                ) {
                    "Extension payloads must not exceed container depth " +
                        "${ExtensionConstraints.MAX_CONTAINER_DEPTH}."
                }
                val members = value.membersForSerialization()
                contractRequire(
                    condition = members.size <= ExtensionConstraints.MAX_OBJECT_MEMBERS,
                    code = "extension_object_member_limit_exceeded",
                    path = path,
                ) {
                    "Extension objects must not exceed " +
                        "${ExtensionConstraints.MAX_OBJECT_MEMBERS} members."
                }
                members.entries
                    .sortedBy { entry -> entry.key }
                    .forEach { (name, child) ->
                        val childPath = extensionChildPath(path, name)
                        requireValidExtensionMemberName(name, childPath)
                        validate(
                            value = child,
                            containerDepth =
                                if (child is ExtensionValue.ArrayValue ||
                                    child is ExtensionValue.ObjectValue
                                ) {
                                    containerDepth + 1
                                } else {
                                    containerDepth
                                },
                            path = childPath,
                        )
                    }
            }
        }
    }
}
