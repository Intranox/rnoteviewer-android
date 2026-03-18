package com.example.rnoteviewer

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.io.File
import java.io.FileOutputStream

class FontsDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "FontsDialog"
        fun newInstance() = FontsDialogFragment()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx   = requireContext()
        val fonts = FontManager.loadedFonts().toMutableList()

        val labels = fonts.map { "${it.familyName}  –  ${it.subfamilyName}" }.toMutableList()

        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, labels) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: LayoutInflater.from(ctx)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                (v as TextView).text = getItem(pos)
                return v
            }
        }

        return AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.fonts_dialog_title))
            .setAdapter(adapter) { _, pos ->
                val font = fonts[pos]
                AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.font_remove_title))
                    .setMessage(getString(R.string.font_remove_msg, font.familyName))
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        FontManager.remove(ctx, font)
                        fonts.removeAt(pos)
                        labels.removeAt(pos)
                        adapter.notifyDataSetChanged()
                        Toast.makeText(ctx,
                            getString(R.string.font_removed, font.familyName), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(getString(R.string.add_font)) { _, _ ->
                (activity as? FontPickerHost)?.openFontPicker()
            }
            .setNegativeButton(getString(R.string.close), null)
            .also { if (fonts.isEmpty()) it.setMessage(getString(R.string.no_fonts_installed)) }
            .create()
    }

    fun installFontFromUri(ctx: Context, uri: Uri) {
        try {
            val cursor = ctx.contentResolver.query(uri, null, null, null, null)
            var name = "font_${System.currentTimeMillis()}.ttf"
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
            if (name.substringAfterLast('.').lowercase() !in listOf("ttf", "otf")) {
                Toast.makeText(ctx, ctx.getString(R.string.font_wrong_type), Toast.LENGTH_SHORT).show()
                return
            }
            // Copia in temp poi installa tramite FontManager (che legge la name table)
            val tmp = File(ctx.cacheDir, name)
            ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                FileOutputStream(tmp).use { out -> ins.copyTo(out) }
            }
            val installed = FontManager.install(ctx, tmp)
            tmp.delete()

            if (installed != null) {
                Toast.makeText(ctx,
                    ctx.getString(R.string.font_installed,
                        "${installed.familyName} (${installed.subfamilyName})"),
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.font_install_error, "formato non supportato"),
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.font_install_error, e.message), Toast.LENGTH_LONG).show()
        }
    }
}

interface FontPickerHost {
    fun openFontPicker()
}
