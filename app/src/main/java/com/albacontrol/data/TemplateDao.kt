package com.albacontrol.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TemplateDao {
    @Query("SELECT * FROM ocr_templates")
    suspend fun getAllTemplates(): List<OCRTemplate>

    @Query("SELECT * FROM template_samples")
    suspend fun getAllSamples(): List<TemplateSample>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: OCRTemplate): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: TemplateSample): Long

    @Query("DELETE FROM ocr_templates")
    suspend fun deleteAllTemplates()

    @Query("DELETE FROM template_samples")
    suspend fun deleteAllSamples()
}
