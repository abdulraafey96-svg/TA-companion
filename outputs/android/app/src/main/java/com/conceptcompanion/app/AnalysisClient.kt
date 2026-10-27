package com.conceptcompanion.app

import android.content.Context
import androidx.room.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.util.Date
import java.util.UUID

data class Explanation(val title: String, val plainEnglish: String, val intuition: String, val workedExample: String, val question: String?, val confirmedContent: String, val uncertainTranscription: String)
data class AnalysisResponse(val documentId: String, val explanation: Explanation, val cached: Boolean)
interface CompanionApi {
    @Multipart @POST("analyze") suspend fun analyze(@Part file: MultipartBody.Part): AnalysisResponse
}
object ApiFactory { fun create(): CompanionApi = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).addConverterFactory(GsonConverterFactory.create()).build().create(CompanionApi::class.java) }

@Entity(tableName = "sessions")
data class StudySession(@PrimaryKey val id: String = UUID.randomUUID().toString(), val title: String, val date: Long = System.currentTimeMillis(), val filename: String, val explanationJson: String, val editedText: String = "")
@Dao interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(session: StudySession)
    @Query("UPDATE sessions SET editedText = :text WHERE id = :id") suspend fun updateCorrection(id: String, text: String)
    @Query("SELECT * FROM sessions ORDER BY date DESC") suspend fun all(): List<StudySession>
}
@Database(entities = [StudySession::class], version = 1)
abstract class CompanionDatabase : RoomDatabase() { abstract fun sessions(): SessionDao
    companion object { @Volatile private var instance: CompanionDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, CompanionDatabase::class.java, "concept-companion.db").build().also { instance = it } }
    }
}

fun makePart(file: File): MultipartBody.Part {
    val mime = when (file.extension.lowercase()) { "pdf" -> "application/pdf"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
    return MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(mime.toMediaType()))
}
