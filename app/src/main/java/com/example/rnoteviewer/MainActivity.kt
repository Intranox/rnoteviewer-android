package com.example.rnoteviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rnoteviewer.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity(), FontPickerHost {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DocumentViewModel by viewModels()

    private var totalPages = 1

    // Handler per nascondere l'indicatore pagina dopo 1.5 s
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideIndicatorRunnable = Runnable {
        binding.pageIndicator.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.pageIndicator.visibility = View.GONE }
            .start()
    }

    // ── Launchers ─────────────────────────────

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadUri(it) } }

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val dialog = supportFragmentManager
            .findFragmentByTag(FontsDialogFragment.TAG) as? FontsDialogFragment
            ?: FontsDialogFragment.newInstance()
        dialog.installFontFromUri(this, uri)
        if (!dialog.isAdded) FontsDialogFragment.newInstance()
            .show(supportFragmentManager, FontsDialogFragment.TAG)
    }

    // ── Lifecycle ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnOpenFile.setOnClickListener { openFileLauncher.launch(arrayOf("*/*")) }
        binding.fabJumpPage.setOnClickListener { showJumpDialog() }

        setupScrollbar()
        setupScrollListener()
        observeViewModel()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ── Menu ──────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open  -> { openFileLauncher.launch(arrayOf("*/*")); true }
        R.id.action_fonts -> {
            FontsDialogFragment.newInstance().show(supportFragmentManager, FontsDialogFragment.TAG)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ── FontPickerHost ────────────────────────

    override fun openFontPicker() { fontPickerLauncher.launch(arrayOf("*/*")) }

    // ── Scrollbar ─────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollbar() {
        binding.scrollbarContainer.setOnTouchListener { _, event ->
            val container = binding.scrollbarContainer
            val progress = (event.y / container.height).coerceIn(0f, 1f)
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    binding.rnoteView.scrollToProgress(progress)
                    showPageIndicator(binding.rnoteView.currentPage())
                }
            }
            true
        }
    }

    private fun setupScrollListener() {
        binding.rnoteView.setScrollListener { progress, page, total ->
            totalPages = total
            // Aggiorna posizione thumb
            val container = binding.scrollbarContainer
            val thumbH = binding.scrollThumb.height.toFloat()
            val maxY   = (container.height - thumbH).coerceAtLeast(0f)
            binding.scrollThumb.translationY = progress * maxY
        }
    }

    /** Mostra l'indicatore pagina per 1.5 s. */
    private fun showPageIndicator(page: Int) {
        binding.pageIndicator.text = getString(R.string.page_indicator, page, totalPages)
        binding.pageIndicator.alpha = 1f
        binding.pageIndicator.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideIndicatorRunnable)
        uiHandler.postDelayed(hideIndicatorRunnable, 1500)
    }

    // ── Jump to page dialog ────────────────────

    private fun showJumpDialog() {
        val input = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.jump_hint, totalPages)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.jump_title))
            .setView(input)
            .setPositiveButton(getString(R.string.go)) { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null && page in 1..totalPages) {
                    binding.rnoteView.jumpToPage(page)
                    showPageIndicator(page)
                } else {
                    Toast.makeText(this,
                        getString(R.string.jump_invalid, totalPages), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── ViewModel ─────────────────────────────

    private fun observeViewModel() {
        viewModel.loadState.observe(this) { state ->
            when (state) {
                is LoadState.Idle    -> showWelcome()
                is LoadState.Loading -> {
                    binding.welcome.visibility          = View.GONE
                    binding.progressBar.visibility      = View.VISIBLE
                    binding.rnoteView.visibility        = View.GONE
                    binding.tvLoading.visibility        = View.VISIBLE
                    binding.scrollbarContainer.visibility = View.GONE
                    binding.fabJumpPage.visibility      = View.GONE
                }
                is LoadState.Success -> {
                    binding.progressBar.visibility      = View.GONE
                    binding.tvLoading.visibility        = View.GONE
                    binding.welcome.visibility          = View.GONE
                    binding.rnoteView.visibility        = View.VISIBLE
                    binding.scrollbarContainer.visibility = View.VISIBLE
                    binding.fabJumpPage.visibility      = View.VISIBLE

                    totalPages = (state.doc.totalHeight / state.doc.pageHeight)
                        .toInt().coerceAtLeast(1)
                    supportActionBar?.title = getString(R.string.title_pages, totalPages)
                    binding.rnoteView.setDocument(state.doc, state.tree)
                }
                is LoadState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvLoading.visibility   = View.GONE
                    showWelcome()
                    Toast.makeText(this,
                        getString(R.string.error_loading, state.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showWelcome() {
        binding.welcome.visibility          = View.VISIBLE
        binding.rnoteView.visibility        = View.GONE
        binding.progressBar.visibility      = View.GONE
        binding.tvLoading.visibility        = View.GONE
        binding.scrollbarContainer.visibility = View.GONE
        binding.fabJumpPage.visibility      = View.GONE
        supportActionBar?.title             = getString(R.string.app_name)
    }

    private fun loadUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
        viewModel.loadFile(uri)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { loadUri(it) }
    }
}
