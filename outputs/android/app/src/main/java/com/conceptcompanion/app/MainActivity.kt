package com.conceptcompanion.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var imported by mutableStateOf<File?>(null)
    private var error by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); consume(intent); show() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consume(intent) }
    private fun consume(intent: Intent?) {
        val uri = ShareFiles.uris(intent).firstOrNull()
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            if (uri == null) error = "The share did not include a readable file. Try exporting it from Samsung Notes again."
            else receive(uri)
        }
    }
    private fun receive(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* Many share providers grant only temporary access. Copy immediately below. */ }
        lifecycleScope.launch {
            try {
                val name = queryName(uri) ?: "shared-document"
                val mime = contentResolver.getType(uri)
                if (!ShareFiles.supported(mime) && !name.endsWith(".pdf", true) && !name.matches(Regex(".*\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE))) throw IllegalArgumentException("Unsupported file. Choose a PDF, PNG, JPEG, or WebP image.")
                val suffix = when { name.endsWith(".pdf", true) -> ".pdf"; name.endsWith(".png", true) -> ".png"; name.endsWith(".webp", true) -> ".webp"; else -> ".jpg" }
                val out = File(cacheDir, "import-${System.currentTimeMillis()}$suffix")
                withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } } ?: error("Could not open the shared content URI.") }
                imported?.takeIf { it != out }?.delete()
                imported = out; error = null
            } catch (e: Exception) { error = e.message ?: "Could not import this file." }
        }
    }
    private fun queryName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) c.getString(i) else null }
    private fun show() { setContent { MaterialTheme { HomeScreen(imported, error, { uri -> receive(uri) }, { error = it }, { imported?.delete(); imported = null }) } } }
}

@Composable private fun HomeScreen(file: File?, incomingError: String?, onPicked: (Uri) -> Unit, onError: (String) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycleScope
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    var loading by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }; var result by remember { mutableStateOf<AnalysisResponse?>(null) }
    var correction by remember(result) { mutableStateOf("") }
    var savedSessionId by remember(result) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) { result = null; correction = ""; savedSessionId = null; message = null }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        Column(Modifier.fillMaxWidth().widthIn(max = 1100.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Concept Companion", style = MaterialTheme.typography.headlineLarge)
            Text("Share handwritten study material, then explore it one concept at a time.", style = MaterialTheme.typography.bodyLarge)
            incomingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, enabled = !loading) { Text("Choose PDF or image") }
            file?.let { f ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium)
                        Preview(f)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = !loading, onClick = {
                                loading = true; message = null; result = null
                                scope.launch {
                                    try {
                                        val response = withContext(Dispatchers.IO) { ApiFactory.create().analyze(makePart(f)) }
                                        result = response
                                        val dao = CompanionDatabase.get(context).sessions()
                                        val session = StudySession(title = response.explanation.title, filename = f.name, explanationJson = Gson().toJson(response.explanation))
                                        withContext(Dispatchers.IO) { dao.save(session) }
                                        savedSessionId = session.id
                                    } catch (e: Exception) { message = e.message ?: "Analysis failed. Check your backend connection and try again." }
                                    finally { loading = false }
                                }
                            }) { Text("Analyze") }
                            OutlinedButton(onClick = { onClear(); message = null; result = null }) { Text("Remove") }
                        }
                    }
                }
            } ?: Text("No material imported yet. Share a PDF or image from Samsung Notes, or choose a file above.", style = MaterialTheme.typography.bodyLarge)
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading your material…") }
            message?.let { Text("Could not analyze: $it", color = MaterialTheme.colorScheme.error) }
            result?.let { response ->
                ExplanationCard(response)
                OutlinedTextField(value = correction, onValueChange = { correction = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Your correction or study note (optional)") }, minLines = 2)
                Button(enabled = savedSessionId != null, onClick = {
                    val id = savedSessionId ?: return@Button
                    scope.launch { withContext(Dispatchers.IO) { CompanionDatabase.get(context).sessions().updateCorrection(id, correction) }; message = "Your correction was saved on this device." }
                }) { Text("Save correction") }
            }
            Text("Your files are uploaded only when you tap Analyze. Explanations and corrections are saved on this device.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun Preview(file: File) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) { bitmap = withContext(Dispatchers.IO) { try {
        if (file.extension.equals("pdf", true)) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { page -> val scale = minOf(1.2f, 1600f / maxOf(page.width, page.height)); Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } } } } }
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) { null } } }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Imported page preview", modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) } ?: Text("Preview unavailable. The file can still be analyzed.")
}

@Composable private fun ExplanationCard(response: AnalysisResponse) {
    val e = response.explanation
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(e.title, style = MaterialTheme.typography.headlineSmall)
        Section("In plain English", e.plainEnglish); Section("Intuition", e.intuition); Section("Worked example", e.workedExample)
        if (!e.confirmedContent.isNullOrBlank()) Section("Content identified", e.confirmedContent)
        if (!e.uncertainTranscription.isNullOrBlank()) Section("Uncertain transcription", e.uncertainTranscription)
        e.question?.let { Section("Check your understanding", it) }
        if (response.cached) Text("Loaded from a prior identical upload.", style = MaterialTheme.typography.labelSmall)
    } }
}
@Composable private fun Section(label: String, body: String) { Text(label, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyLarge) }
