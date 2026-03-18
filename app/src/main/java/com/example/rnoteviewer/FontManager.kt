package com.example.rnoteviewer

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages custom fonts saved in [fontsDir] = `<filesDir>/fonts/`.
 *
 * ### Automatic indexing
 * When a .ttf/.otf file is installed, the [readTtfFamilyName] function reads
 * the OpenType file’s Name Table to extract:
 *  - nameID 1 = Font Family  (e.g. "Indie Flower")
 *  - nameID 2 = Font Subfamily (e.g. "Regular", "Bold Italic")
 *
 * The font is then indexed with TWO keys:
 *  1. "<family>"              → e.g. "indie flower"
 *  2. "<family> <subfamily>"  → e.g. "indie flower regular"
 *
 * ### Resolution (family, style)
 *  1. Search "<family> <style>"  (e.g. "indie flower regular")
 *  2. Search "<family>"          (e.g. "indie flower")
 *  3. Search by prefix
 *  4. Synthetic fallback to Typeface.DEFAULT
 */

object FontManager {

    data class InstalledFont(
        val file: File,
        val familyName: String,
        val subfamilyName: String
    )

    private val byKey     = LinkedHashMap<String, Typeface>()     // key → typeface
    private val installed = mutableListOf<InstalledFont>()
    private var loaded    = false

    fun fontsDir(context: Context): File =
        File(context.filesDir, "fonts").also { it.mkdirs() }

    fun init(context: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        byKey.clear()
        installed.clear()

        fontsDir(context).listFiles { f ->
            f.extension.lowercase() in listOf("ttf", "otf")
        }?.forEach { loadFile(it) }

        Log.i("FontManager", "Font caricati (${byKey.size} chiavi): ${byKey.keys}")
    }

    /** Installa un font copiandolo da [src] e indicizzandolo. */
    fun install(context: Context, src: File): InstalledFont? {
        if (src.extension.lowercase() !in listOf("ttf", "otf")) return null
        return try {
            val dest = File(fontsDir(context), src.name)
            src.copyTo(dest, overwrite = true)
            loadFile(dest)
            installed.lastOrNull { it.file == dest }
        } catch (e: Exception) {
            Log.e("FontManager", "Errore installazione: ${e.message}")
            null
        }
    }

    fun remove(context: Context, font: InstalledFont) {
        // Rimuovi tutte le chiavi che puntano a questo file
        byKey.entries.removeAll { it.value == byKey[font.familyName.lowercase()] }
        installed.remove(font)
        font.file.delete()
    }

    fun loadedFonts(): List<InstalledFont> = installed.toList()

    fun resolve(family: String, style: String): Typeface {
        val fam = family.lowercase().trim()
        val sty = style.lowercase().trim()

        if (fam.isNotEmpty()) {
            // 1. Exact family + style
            if (sty.isNotEmpty() && sty != "regular") {
                byKey["$fam $sty"]?.let { return it }
            }
            // 2. Family only
            byKey[fam]?.let { return it }
            // 3. Prefix match
            byKey.entries.firstOrNull { it.key.startsWith(fam) }?.value?.let { return it }
        }
        // 4. Synthetic fallback
        val synth = when {
            "bold" in sty && "italic" in sty -> Typeface.BOLD_ITALIC
            "bold"   in sty                  -> Typeface.BOLD
            "italic" in sty                  -> Typeface.ITALIC
            else                             -> Typeface.NORMAL
        }
        return Typeface.create(Typeface.DEFAULT, synth)
    }

    // ── Private ───────────────────────────────

    private fun loadFile(file: File) {
        try {
            val tf      = Typeface.createFromFile(file)
            val names   = readTtfFamilyName(file)
            val family  = names.first.ifBlank  { file.nameWithoutExtension }
            val subFam  = names.second.ifBlank { "Regular" }

            val keyFamily = family.lowercase().trim()
            val keySub    = "$keyFamily ${subFam.lowercase().trim()}"

            byKey[keyFamily] = tf
            byKey[keySub]    = tf

            // Also index by raw filename stem (fallback)
            val keyStem = file.nameWithoutExtension.lowercase().trim()
            if (keyStem != keyFamily) byKey[keyStem] = tf

            installed.add(InstalledFont(file, family, subFam))
            Log.d("FontManager", "Caricato \"$family\" ($subFam) → chiavi: $keyFamily, $keySub")
        } catch (e: Exception) {
            Log.w("FontManager", "Impossibile caricare ${file.name}: ${e.message}")
        }
    }

/**
 * Reads the Name Table of a TTF/OTF file and returns
 * (familyName, subfamilyName).
 *
 * OpenType name table structure (offset from the end of the offset table):
 *   UInt16  format
 *   UInt16  count        — number of records
 *   UInt16  stringOffset — offset of the string storage from the start of the table
 *   [count × NameRecord (12 bytes)]
 *   [string storage]
 *
 * NameRecord:
 *   UInt16 platformID  (1=Mac, 3=Windows)
 *   UInt16 encodingID
 *   UInt16 languageID  (Windows: 0x0409=en-US, 0x0410=it)
 *   UInt16 nameID      (1=Family, 2=Subfamily)
 *   UInt16 length
 *   UInt16 offset
 */
    private fun readTtfFamilyName(file: File): Pair<String, String> {
        var family   = ""
        var subFamily = ""
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buf4 = ByteArray(4)
                val buf2 = ByteArray(2)

                fun readU16(): Int {
                    raf.read(buf2)
                    return ((buf2[0].toInt() and 0xFF) shl 8) or (buf2[1].toInt() and 0xFF)
                }
                fun readU32(): Long {
                    raf.read(buf4)
                    return ((buf4[0].toLong() and 0xFF) shl 24) or
                           ((buf4[1].toLong() and 0xFF) shl 16) or
                           ((buf4[2].toLong() and 0xFF) shl  8) or
                           ( buf4[3].toLong() and 0xFF)
                }

                // Offset Table
                val sfVersion = readU32()
                val numTables = readU16()
                readU16(); readU16(); readU16() // searchRange, entrySelector, rangeShift

                // Trova la "name" table
                var nameTableOffset = -1L
                repeat(numTables) {
                    val tag = ByteArray(4).also { raf.read(it) }
                    val checksum = readU32()
                    val offset   = readU32()
                    val length   = readU32()
                    if (String(tag) == "name") nameTableOffset = offset
                }
                if (nameTableOffset < 0) return Pair("", "")

                raf.seek(nameTableOffset)
                val format       = readU16()
                val count        = readU16()
                val stringOffset = readU16()
                val storageBase  = nameTableOffset + stringOffset

                // Record interessanti: (platformID=3,langID=0x0409) o (platformID=1)
                // nameID 1=family, 2=subfamily
                data class Record(val platId:Int,val langId:Int,val nameId:Int,val len:Int,val off:Int)
                val records = (0 until count).map {
                    val platId = readU16()
                    val encId  = readU16()
                    val langId = readU16()
                    val nameId = readU16()
                    val len    = readU16()
                    val off    = readU16()
                    Record(platId, langId, nameId, len, off)
                }

                fun readString(r: Record): String {
                    raf.seek(storageBase + r.off)
                    val bytes = ByteArray(r.len)
                    raf.read(bytes)
                    return if (r.platId == 3) {
                        // Windows: UTF-16 BE
                        String(bytes, Charsets.UTF_16BE)
                    } else {
                        // Mac: Latin-1 approx
                        String(bytes, Charsets.ISO_8859_1)
                    }
                }

                // Preferisci Windows English (platId=3, langId=0x0409), poi qualsiasi
                fun bestRecord(nameId: Int): String {
                    val win = records.firstOrNull { it.nameId == nameId && it.platId == 3 && it.langId == 0x0409 }
                        ?: records.firstOrNull { it.nameId == nameId && it.platId == 3 }
                        ?: records.firstOrNull { it.nameId == nameId && it.platId == 1 }
                        ?: records.firstOrNull { it.nameId == nameId }
                        ?: return ""
                    return readString(win)
                }

                family    = bestRecord(1)
                subFamily = bestRecord(2)
            }
        } catch (e: Exception) {
            Log.w("FontManager", "readTtfFamilyName failed for ${file.name}: ${e.message}")
        }
        return Pair(family, subFamily)
    }
}
