package com.albacontrol.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.*

// Conversores simples para Map/JSON (placeholder). Se recomienda usar Gson/Moshi en el proyecto.
class Converters {
    @TypeConverter
    fun fromMap(value: Map<String, String>?): String? {
        return value?.entries?.joinToString(";;") { "${it.key}::${it.value}" }
    }

    @TypeConverter
    fun toMap(value: String?): Map<String, String>? {
        if (value == null || value.isEmpty()) return null
        return value.split(";;").map {
            val parts = it.split("::", limit = 2)
            parts[0] to (parts.getOrElse(1) { "" })
        }.toMap()
    }
}

@Entity(tableName = "providers")
data class Provider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nif: String
)

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    val sku: String?
)

@Entity(tableName = "drafts")
@TypeConverters(Converters::class)
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long?,
    @ColumnInfo(name = "data_json") val dataJson: String, // JSON con campos del formulario
    val createdAt: Long = Date().time,
    val updatedAt: Long = Date().time
)

@Entity(tableName = "completed_albaranes")
data class CompletedAlbaran(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long?,
    @ColumnInfo(name = "data_json") val dataJson: String,
    val createdAt: Long = Date().time
)

@Entity(tableName = "ocr_templates")
@TypeConverters(Converters::class)
data class OCRTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerNif: String,
    @ColumnInfo(name = "mappings") val mappings: Map<String, String>, // fieldName -> bboxNormalized (x,y,w,h) as string
    val version: Int = 1,
    val active: Boolean = true,
    val createdFromSampleIds: String? = null,
    val fieldConfidence: Map<String, String>? = null
)

@Entity(tableName = "template_samples")
@TypeConverters(Converters::class)
data class TemplateSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerNif: String,
    val imagePath: String,
    @ColumnInfo(name = "field_mappings") val fieldMappings: Map<String, String>, // fieldName -> bbox+recognizedText
    val createdAt: Long = Date().time,
    val normalizedFields: Map<String, String>? = null,
    val fieldConfidences: Map<String, String>? = null
)
