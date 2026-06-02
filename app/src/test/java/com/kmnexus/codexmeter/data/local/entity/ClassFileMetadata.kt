package com.kmnexus.codexmeter.data.local.entity

import java.io.DataInputStream

internal data class ClassFileMetadata(
    val annotations: List<AnnotationMetadata>,
    val fields: List<FieldMetadata>,
) {
    fun requireClassAnnotation(annotationClass: Class<out Annotation>): AnnotationMetadata =
        annotations.firstOrNull { annotation -> annotation.descriptor == annotationClass.descriptor }
            ?: error("${annotationClass.simpleName} annotation missing from compiled class metadata")

    companion object {
        fun read(entityClass: Class<*>): ClassFileMetadata {
            val resourceName = "${entityClass.name.replace('.', '/')}.class"
            val classLoader = entityClass.classLoader ?: ClassLoader.getSystemClassLoader()
            val bytes = requireNotNull(classLoader.getResourceAsStream(resourceName)) {
                "Compiled class resource missing: $resourceName"
            }.use { stream -> stream.readBytes() }
            return ClassFileReader(bytes).read()
        }
    }
}

internal data class FieldMetadata(
    val name: String,
    val annotations: List<AnnotationMetadata>,
) {
    fun hasAnnotation(annotationClass: Class<out Annotation>): Boolean =
        annotations.any { annotation -> annotation.descriptor == annotationClass.descriptor }

    fun requireAnnotation(annotationClass: Class<out Annotation>): AnnotationMetadata =
        annotations.firstOrNull { annotation -> annotation.descriptor == annotationClass.descriptor }
            ?: error("$name is missing @${annotationClass.simpleName} in compiled class metadata")
}

internal data class AnnotationMetadata(
    val descriptor: String,
    val values: Map<String, AnnotationValue>,
) {
    fun stringValue(name: String): String =
        (values[name] as? AnnotationValue.StringValue)?.value
            ?: error("$descriptor is missing string value '$name'")

    fun stringArray(name: String): List<String> =
        arrayValue(name).map { value ->
            (value as? AnnotationValue.StringValue)?.value
                ?: error("$descriptor array '$name' contains a non-string value")
        }

    fun annotationArray(
        name: String,
        annotationClass: Class<out Annotation>,
    ): List<AnnotationMetadata> =
        arrayValue(name).map { value ->
            val annotation = (value as? AnnotationValue.Annotation)?.metadata
                ?: error("$descriptor array '$name' contains a non-annotation value")
            check(annotation.descriptor == annotationClass.descriptor) {
                "$descriptor array '$name' contains ${annotation.descriptor}, expected ${annotationClass.descriptor}"
            }
            annotation
        }

    private fun arrayValue(name: String): List<AnnotationValue> =
        (values[name] as? AnnotationValue.ArrayValue)?.values
            ?: error("$descriptor is missing array value '$name'")
}

internal sealed class AnnotationValue {
    data class StringValue(val value: String) : AnnotationValue()
    data class Annotation(val metadata: AnnotationMetadata) : AnnotationValue()
    data class ArrayValue(val values: List<AnnotationValue>) : AnnotationValue()
    data class Other(val value: String) : AnnotationValue()
}

private class ClassFileReader(bytes: ByteArray) {
    private val input = DataInputStream(bytes.inputStream())
    private lateinit var constantPool: Array<ConstantPoolEntry?>

    fun read(): ClassFileMetadata {
        check(input.readInt() == CLASS_FILE_MAGIC) { "Invalid class file" }
        input.skipFully(4)
        readConstantPool()
        input.skipFully(6)
        repeat(input.readUnsignedShort()) { input.skipFully(2) }

        val fields = List(input.readUnsignedShort()) { readField() }
        repeat(input.readUnsignedShort()) { skipMember() }
        val annotations = readAttributes()

        return ClassFileMetadata(annotations = annotations, fields = fields)
    }

    private fun readConstantPool() {
        constantPool = arrayOfNulls(input.readUnsignedShort())
        var index = 1
        while (index < constantPool.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> constantPool[index] = ConstantPoolEntry.Utf8(input.readUTF())
                3 -> constantPool[index] = ConstantPoolEntry.IntegerValue(input.readInt())
                4 -> constantPool[index] = ConstantPoolEntry.FloatValue(input.readFloat())
                5 -> {
                    constantPool[index] = ConstantPoolEntry.LongValue(input.readLong())
                    index++
                }
                6 -> {
                    constantPool[index] = ConstantPoolEntry.DoubleValue(input.readDouble())
                    index++
                }
                7 -> constantPool[index] = ConstantPoolEntry.ClassInfo(input.readUnsignedShort())
                8 -> constantPool[index] = ConstantPoolEntry.StringInfo(input.readUnsignedShort())
                9, 10, 11, 12, 17, 18 -> input.skipFully(4)
                15 -> input.skipFully(3)
                16, 19, 20 -> input.skipFully(2)
                else -> error("Unsupported constant pool tag $tag")
            }
            index++
        }
    }

    private fun readField(): FieldMetadata {
        input.skipFully(2)
        val name = utf8(input.readUnsignedShort())
        input.skipFully(2)
        return FieldMetadata(name = name, annotations = readAttributes())
    }

    private fun skipMember() {
        input.skipFully(6)
        repeat(input.readUnsignedShort()) {
            input.skipFully(2)
            input.skipFully(input.readInt())
        }
    }

    private fun readAttributes(): List<AnnotationMetadata> {
        val annotations = mutableListOf<AnnotationMetadata>()
        repeat(input.readUnsignedShort()) {
            val name = utf8(input.readUnsignedShort())
            val length = input.readInt()
            if (name == "RuntimeVisibleAnnotations" || name == "RuntimeInvisibleAnnotations") {
                repeat(input.readUnsignedShort()) {
                    annotations += readAnnotation()
                }
            } else {
                input.skipFully(length)
            }
        }
        return annotations
    }

    private fun readAnnotation(): AnnotationMetadata {
        val descriptor = utf8(input.readUnsignedShort())
        val values = buildMap {
            repeat(input.readUnsignedShort()) {
                put(utf8(input.readUnsignedShort()), readElementValue())
            }
        }
        return AnnotationMetadata(descriptor = descriptor, values = values)
    }

    private fun readElementValue(): AnnotationValue =
        when (val tag = input.readUnsignedByte().toChar()) {
            's' -> AnnotationValue.StringValue(utf8(input.readUnsignedShort()))
            'e' -> {
                val typeName = utf8(input.readUnsignedShort())
                val constantName = utf8(input.readUnsignedShort())
                AnnotationValue.Other("$typeName.$constantName")
            }
            'c' -> AnnotationValue.Other(utf8(input.readUnsignedShort()))
            '@' -> AnnotationValue.Annotation(readAnnotation())
            '[' -> AnnotationValue.ArrayValue(
                List(input.readUnsignedShort()) { readElementValue() },
            )
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> AnnotationValue.Other(
                constantPoolString(input.readUnsignedShort()),
            )
            else -> error("Unsupported annotation element tag '$tag'")
        }

    private fun utf8(index: Int): String =
        (constantPool[index] as? ConstantPoolEntry.Utf8)?.value
            ?: error("Constant pool entry $index is not UTF-8")

    private fun constantPoolString(index: Int): String =
        when (val entry = constantPool[index]) {
            is ConstantPoolEntry.Utf8 -> entry.value
            is ConstantPoolEntry.StringInfo -> utf8(entry.stringIndex)
            is ConstantPoolEntry.ClassInfo -> utf8(entry.nameIndex)
            is ConstantPoolEntry.IntegerValue -> entry.value.toString()
            is ConstantPoolEntry.FloatValue -> entry.value.toString()
            is ConstantPoolEntry.LongValue -> entry.value.toString()
            is ConstantPoolEntry.DoubleValue -> entry.value.toString()
            null -> error("Missing constant pool entry $index")
        }

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            remaining -= skip(remaining.toLong()).toInt().takeIf { skipped -> skipped > 0 }
                ?: run {
                    readByte()
                    1
                }
        }
    }

    private sealed class ConstantPoolEntry {
        data class Utf8(val value: String) : ConstantPoolEntry()
        data class ClassInfo(val nameIndex: Int) : ConstantPoolEntry()
        data class StringInfo(val stringIndex: Int) : ConstantPoolEntry()
        data class IntegerValue(val value: Int) : ConstantPoolEntry()
        data class FloatValue(val value: Float) : ConstantPoolEntry()
        data class LongValue(val value: Long) : ConstantPoolEntry()
        data class DoubleValue(val value: Double) : ConstantPoolEntry()
    }

    private companion object {
        const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
    }
}

internal val Class<*>.descriptor: String
    get() = "L${name.replace('.', '/')};"
