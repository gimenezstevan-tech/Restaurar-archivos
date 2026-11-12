package com.recuperafiles

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

enum class Tipo { IMAGEN, VIDEO, AUDIO }
enum class Origen { PAPELERA, CACHE, CARVING }

data class Hallazgo(
    val nombre: String, val tamano: Long, val tipo: Tipo, val origen: Origen,
    val uri: Uri? = null, val archivo: File? = null, val offset: Long = -1, var elegido: Boolean = false
)

/** 1) Papelera del sistema (Android 11+, se conserva ~30 días). */
fun escanearPapelera(ctx: Context): List<Hallazgo> {
    val out = mutableListOf<Hallazgo>()
    val fuentes = mapOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Tipo.IMAGEN,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Tipo.VIDEO,
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Tipo.AUDIO
    )
    for ((uri, tipo) in fuentes) {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            args, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Hallazgo(c.getString(1) ?: "sin_nombre", c.getLong(2), tipo, Origen.PAPELERA,
                    uri = android.content.ContentUris.withAppendedId(uri, id))
            }
        }
    }
    return out
}

private val EXT = mapOf(
    "jpg" to Tipo.IMAGEN, "jpeg" to Tipo.IMAGEN, "png" to Tipo.IMAGEN, "webp" to Tipo.IMAGEN, "gif" to Tipo.IMAGEN,
    "mp4" to Tipo.VIDEO, "3gp" to Tipo.VIDEO, "mkv" to Tipo.VIDEO,
    "mp3" to Tipo.AUDIO, "m4a" to Tipo.AUDIO, "opus" to Tipo.AUDIO, "amr" to Tipo.AUDIO, "wav" to Tipo.AUDIO
)

/** 2) Archivos .trashed-*, miniaturas, caché y carpetas ocultas accesibles. */
fun escanearCache(ctx: Context): List<Hallazgo> {
    val raices = listOf(Environment.getExternalStorageDirectory(), ctx.cacheDir, ctx.externalCacheDir).filterNotNull()
    val out = mutableListOf<Hallazgo>()
    for (raiz in raices) raiz.walkTopDown()
        .onEnter { it.canRead() }
        .filter { it.isFile && it.length() > 2048 }
        .forEach { f ->
            val ext = f.extension.lowercase()
            val oculto = f.name.startsWith(".trashed") || f.path.contains("/.thumbnails/") ||
                f.path.contains("/.Trash") || f.path.contains("/cache/", true) || f.path.contains("/.Statuses/")
            val tipo = EXT[ext] ?: if (f.name.startsWith(".trashed")) EXT[f.name.substringAfterLast('.').lowercase()] else null
            if (oculto && tipo != null) out += Hallazgo(f.name, f.length(), tipo, Origen.CACHE, archivo = f)
        }
    return out
}

/** 3) Carving: busca firmas de archivo en una imagen/dispositivo de bloques (root o tarjeta SD). */
private class Firma(val ext: String, val tipo: Tipo, val cab: ByteArray, val pie: ByteArray?, val off: Int, val max: Int)
private val FIRMAS = listOf(
    Firma("jpg", Tipo.IMAGEN, byteArrayOf(-1, -40, -1), byteArrayOf(-1, -39), 0, 25_000_000),
    Firma("png", Tipo.IMAGEN, byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), byteArrayOf(73, 69, 78, 68, -82, 66, 96, -126), 0, 25_000_000),
    Firma("mp4", Tipo.VIDEO, "ftyp".toByteArray(), null, 4, 400_000_000),
    Firma("mp3", Tipo.AUDIO, "ID3".toByteArray(), null, 0, 20_000_000),
    Firma("m4a", Tipo.AUDIO, "M4A ".toByteArray(), null, 8, 50_000_000)
)

/** Abre el almacenamiento crudo: requiere root. Pasa la ruta, p.ej. /dev/block/by-name/userdata */
fun abrirDispositivoRoot(ruta: String): InputStream =
    ProcessBuilder("su", "-c", "cat $ruta").redirectErrorStream(false).start().inputStream

fun carving(entrada: InputStream, destino: File, progreso: (Long) -> Unit): List<Hallazgo> {
    destino.mkdirs()
    val buf = ByteArray(8 * 1024 * 1024)
    val out = mutableListOf<Hallazgo>()
    var base = 0L
    var n = 0
    while (true) {
        val r = entrada.read(buf); if (r <= 0) break
        for (f in FIRMAS) {
            var i = 0
            while (i < r - 64) {
                if (coincide(buf, i + 0, f.cab, f.off)) {
                    val ini = i - f.off
                    if (ini < 0) { i++; continue }
                    val fin = buscarPie(buf, ini + f.cab.size, r, f.pie, f.max)
                    if (fin > ini + 4096) {
                        val file = File(destino, "recuperado_${++n}.${f.ext}")
                        file.outputStream().use { it.write(buf, ini, fin - ini) }
                        out += Hallazgo(file.name, file.length(), f.tipo, Origen.CARVING, archivo = file, offset = base + ini)
                        i = fin; continue
                    }
                }
                i++
            }
        }
        base += r; progreso(base)
    }
    return out
}
private fun coincide(b: ByteArray, i: Int, s: ByteArray, off: Int): Boolean {
    val p = i; if (p + s.size > b.size) return false
    for (k in s.indices) if (b[p + k] != s[k]) return false
    return true
}
private fun buscarPie(b: ByteArray, desde: Int, limite: Int, pie: ByteArray?, max: Int): Int {
    val tope = minOf(limite, desde + max)
    if (pie == null) return tope
    var i = desde
    while (i < tope - pie.size) { if (coincide(b, i, pie, 0)) return i + pie.size; i++ }
    return -1
}

/** Restaura un hallazgo a Pictures/Movies/Music/Recuperados. */
fun restaurar(ctx: Context, h: Hallazgo): Boolean {
    val src = h.archivo ?: return false
    val carpeta = when (h.tipo) { Tipo.IMAGEN -> Environment.DIRECTORY_PICTURES; Tipo.VIDEO -> Environment.DIRECTORY_MOVIES; Tipo.AUDIO -> Environment.DIRECTORY_MUSIC }
    val dest = File(Environment.getExternalStoragePublicDirectory(carpeta), "Recuperados").apply { mkdirs() }
    src.copyTo(File(dest, h.nombre.removePrefix(".trashed-").substringAfter('-', h.nombre)), overwrite = false)
    android.media.MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), null, null)
    return true
}

// ───────────── Específico Samsung Galaxy A05s · One UI 6 (Android 14) ─────────────

/** Detecta el tipo por cabecera: las papeleras de Samsung suelen renombrar archivos sin extensión. */
fun tipoPorCabecera(f: File): Tipo? = runCatching {
    val h = ByteArray(12); f.inputStream().use { it.read(h) }
    fun s(o: Int, t: String) = String(h, o, t.length, Charsets.ISO_8859_1) == t
    when {
        h[0] == (-1).toByte() && h[1] == (-40).toByte() -> Tipo.IMAGEN          // JPEG
        s(1, "PNG") || s(0, "RIFF") && s(8, "WEBP") || s(0, "GIF8") -> Tipo.IMAGEN
        s(4, "ftyp") -> if (s(8, "M4A ")) Tipo.AUDIO else Tipo.VIDEO            // MP4/HEIC/M4A
        s(0, "ID3") || h[0] == (-1).toByte() && (h[1].toInt() and 0xE0) == 0xE0 -> Tipo.AUDIO
        s(0, "#!AMR") || s(0, "OggS") || s(0, "RIFF") && s(8, "WAVE") -> Tipo.AUDIO
        else -> null
    }
}.getOrNull()

/**
 * Papeleras de Galería y Mis archivos de Samsung. Con "acceso a todos los archivos" Android 14 permite
 * leer Android/.Trash (solo Android/data y Android/obb están bloqueadas). RUTAS A VERIFICAR en tu equipo.
 */
fun escanearPapeleraSamsung(): List<Hallazgo> {
    val base = Environment.getExternalStorageDirectory()
    val candidatas = listOf("Android/.Trash", "Android/.globalTrash", ".Trash", ".globalTrash", "DCIM/.Trash", "Pictures/.Trash")
    val out = mutableListOf<Hallazgo>()
    for (c in candidatas) File(base, c).takeIf { it.isDirectory && it.canRead() }?.walkTopDown()
        ?.filter { it.isFile && it.length() > 2048 }?.forEach { f ->
            tipoPorCabecera(f)?.let { out += Hallazgo(f.name, f.length(), it, Origen.PAPELERA, archivo = f) }
        }
    return out
}

/** Tarjeta microSD (el A05s tiene ranura): ruta montada o null. */
fun rutaMicroSD(ctx: Context): File? =
    (ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager).storageVolumes
        .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }?.directory

fun hayRoot(): Boolean = runCatching {
    val p = ProcessBuilder("su", "-c", "id").start(); p.waitFor() == 0
}.getOrDefault(false)

/** En el A05s: la microSD es /dev/block/mmcblk1 (sin cifrar → el carving sí funciona). La interna está cifrada (FBE). */
const val DISPOSITIVO_SD = "/dev/block/mmcblk1"
