package com.recuperafiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun EXT_TIPO(f: File) = mapOf("jpg" to Tipo.IMAGEN,"jpeg" to Tipo.IMAGEN,"png" to Tipo.IMAGEN,"mp4" to Tipo.VIDEO,"mp3" to Tipo.AUDIO,"m4a" to Tipo.AUDIO,"opus" to Tipo.AUDIO)[f.name.substringAfterLast('.').lowercase()]

class MainActivity : ComponentActivity() {
       override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val log = File(filesDir, "crash.txt")
        val previo = log.takeIf { it.exists() }?.readText()
        log.delete()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            log.writeText(e.stackTraceToString())
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        setContent { MaterialTheme {
            if (previo != null) Text(previo.take(1500), Modifier.padding(16.dp)) else Pantalla()
        } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Pantalla() {
        val scope = rememberCoroutineScope()
        var paso by remember { mutableStateOf(0) }            // 0 inicio, 1 escaneando, 2 resultados
        var estado by remember { mutableStateOf("") }
        val hallazgos = remember { mutableStateListOf<Hallazgo>() }
        var filtro by remember { mutableStateOf<Tipo?>(null) }
        val restaurarPapelera = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

        fun pedirAccesoTotal() {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }

        fun escanear(profundo: Boolean) = scope.launch {
            paso = 1; hallazgos.clear()
            withContext(Dispatchers.IO) {
                estado = "Buscando en la papelera…"; hallazgos += escanearPapelera(this@MainActivity)
                estado = "Buscando en la papelera de Samsung…"; hallazgos += escanearPapeleraSamsung()
                estado = "Buscando en caché y miniaturas…"; hallazgos += escanearCache(this@MainActivity)
                rutaMicroSD(this@MainActivity)?.let { sd -> estado = "Revisando la tarjeta microSD…"
                    sd.walkTopDown().onEnter { it.canRead() }.filter { it.isFile && it.length() > 2048 && (it.name.startsWith(".trashed") || it.path.contains("/.Trash")) }
                        .forEach { f -> (EXT_TIPO(f) ?: tipoPorCabecera(f))?.let { hallazgos += Hallazgo(f.name, f.length(), it, Origen.CACHE, archivo = f) } } }
                if (profundo) {
                    estado = "Escaneo profundo de la microSD (requiere root)…"
                    if (!hayRoot()) { estado = "Este teléfono no tiene root: usa un lector de tarjetas en PC para el escaneo profundo" } else runCatching {
                        hallazgos += carving(abrirDispositivoRoot(DISPOSITIVO_SD),
                            File(getExternalFilesDir(null), "carving")) { b -> estado = "Analizados ${b / 1_000_000} MB" }
                    }.onFailure { estado = "Sin acceso root: se omitió el escaneo profundo" }
                }
            }
            paso = 2
        }

        Scaffold(topBar = { TopAppBar(title = { Text("RecuperaFiles") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
                when (paso) {
                    0 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Recupera fotos, videos y audios eliminados.", style = MaterialTheme.typography.titleMedium)
                        Button({ pedirAccesoTotal() }, Modifier.fillMaxWidth()) { Text("1. Dar acceso a los archivos") }
                        Button({ escanear(false) }, Modifier.fillMaxWidth()) { Text("2. Escaneo rápido (papelera y caché)") }
                        OutlinedButton({ escanear(true) }, Modifier.fillMaxWidth()) { Text("Escaneo profundo de la microSD (root)") }
                    }
                    1 -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(estado)
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(filtro == null, { filtro = null }, { Text("Todo") })
                            Tipo.values().forEach { t -> FilterChip(filtro == t, { filtro = t }, { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                        }
                        val lista = hallazgos.filter { filtro == null || it.tipo == filtro }
                        Text("${lista.size} archivos encontrados", Modifier.padding(vertical = 8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(lista) { h ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(h.elegido, { h.elegido = it; val i = hallazgos.indexOf(h); hallazgos[i] = h.copy(elegido = it) })
                                    Column { Text(h.nombre); Text("${h.tipo} · ${h.origen} · ${h.tamano / 1024} KB", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        Button({
                            val sel = hallazgos.filter { it.elegido }
                            val papelera = sel.filter { it.origen == Origen.PAPELERA }.mapNotNull { it.uri }
                            if (papelera.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                                val pi = MediaStore.createTrashRequest(contentResolver, papelera, false)
                                restaurarPapelera.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                            }
                            scope.launch(Dispatchers.IO) { sel.filter { it.origen != Origen.PAPELERA }.forEach { restaurar(this@MainActivity, it) } }
                        }, Modifier.fillMaxWidth()) { Text("Recuperar seleccionados") }
                    }
                }
            }
        }
    }
}
