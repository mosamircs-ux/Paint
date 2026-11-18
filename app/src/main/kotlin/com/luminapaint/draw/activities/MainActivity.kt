package com.luminapaint.draw.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.print.PrintHelper
import com.luminapaint.draw.BuildConfig
import com.luminapaint.draw.R
import com.luminapaint.draw.ads.AdManager
import com.luminapaint.draw.databinding.ActivityMainBinding
import com.luminapaint.draw.dialogs.SaveImageDialog
import com.luminapaint.draw.extensions.config
import com.luminapaint.draw.helpers.EyeDropper
import com.luminapaint.draw.helpers.JPG
import com.luminapaint.draw.helpers.PNG
import com.luminapaint.draw.helpers.SVG
import com.luminapaint.draw.interfaces.CanvasListener
import com.luminapaint.draw.models.Svg
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.dialogs.ConfirmationAdvancedDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getCompressionFormat
import org.fossify.commons.extensions.getFileOutputStream
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isImageSlow
import org.fossify.commons.extensions.onSeekBarChangeListener
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.setFillWithStroke
import org.fossify.commons.extensions.sharePathIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.PERMISSION_WRITE_STORAGE
import org.fossify.commons.helpers.SAVE_DISCARD_PROMPT_INTERVAL
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.FileDirItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max

class MainActivity : SimpleActivity(), CanvasListener {
    companion object {
        private const val PICK_IMAGE_INTENT = 1
        private const val SAVE_IMAGE_INTENT = 2

        private const val FOLDER_NAME = "images"
        private const val FILE_NAME = "lumina-paint.png"
        private const val BITMAP_PATH = "bitmap_path"
        private const val URI_TO_LOAD = "uri_to_load"
        private const val HAS_CONTENT = "has_content"

        private val STUDIO_SWATCH_COLORS = intArrayOf(
            0xFF4F46E5.toInt(), // Deep Indigo
            0xFFFF5A79.toInt(), // Vibrant Coral
            0xFFFFB84D.toInt(), // Warm Amber
            0xFF10B981.toInt(), // Studio Emerald
            0xFF38BDF8.toInt(), // Electric Cyan
            0xFF8B5CF6.toInt(), // Royal Violet
            0xFFE11D48.toInt(), // Crimson Rose
            0xFF0D9488.toInt(), // Deep Teal
            0xFF1E1B4B.toInt(), // Studio Charcoal
            0xFFFFFFFF.toInt()  // Pure White
        )
    }

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private lateinit var eyeDropper: EyeDropper
    private lateinit var adManager: AdManager
    private val swatchViews = mutableListOf<Pair<Int, View>>()

    private var defaultPath = ""
    private var defaultFilename = ""
    private var defaultExtension = PNG

    private var intentUri: Uri? = null
    private var uriToLoad: Uri? = null
    private var color = 0
    private var brushSize = 0f
    private var savedPathsHash = 0L
    private var lastSavePromptTS = 0L
    private var isEraserOn = false
    private var isEyeDropperOn = false
    private var isBucketFillOn = false
    private var isImageCaptureIntent = false
    private var isEditIntent = false
    private var lastBitmapPath = ""
    private var hasContent = false
    private var canUndo = false
    private var canRedo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(
            padBottomSystem = listOf(binding.adBannerHolder),
        )

        adManager = AdManager(this)
        adManager.loadAdaptiveBanner(this, binding.adContainer)
        adManager.preloadInterstitial()

        eyeDropper = EyeDropper(binding.myCanvas) { selectedColor ->
            setColor(selectedColor)
        }

        binding.myCanvas.mListener = this
        binding.strokeWidthBar.onSeekBarChangeListener { progress ->
            brushSize = max(progress.toFloat(), 5f)
            updateBrushSize()
        }

        setupQuickColorSwatches()

        setBackgroundColor(config.canvasBackgroundColor)
        setColor(config.brushColor)
        defaultPath = config.lastSaveFolder
        defaultExtension = config.lastSaveExtension

        brushSize = config.brushSize
        updateBrushSize()
        binding.strokeWidthBar.progress = brushSize.toInt()

        binding.apply {
            brushTool.setOnClickListener { activateBrushTool() }
            brushTool.setOnLongClickListener {
                toast(R.string.brush)
                true
            }

            colorPicker.setOnClickListener { pickColor() }
            undo.setOnClickListener { myCanvas.undo() }
            undo.setOnLongClickListener {
                toast(R.string.undo)
                true
            }

            eraser.setOnClickListener { eraserClicked() }
            eraser.setOnLongClickListener {
                toast(R.string.eraser)
                true
            }

            redo.setOnClickListener { myCanvas.redo() }
            redo.setOnLongClickListener {
                toast(R.string.redo)
                true
            }

            eyeDropper.setOnClickListener { eyeDropperClicked() }
            eyeDropper.setOnLongClickListener {
                toast(R.string.eyedropper)
                true
            }

            bucketFill.setOnClickListener { bucketFillClicked() }
            bucketFill.setOnLongClickListener {
                toast(R.string.bucket_fill)
                true
            }
        }

        checkIntents()
    }

    override fun onResume() {
        super.onResume()
        adManager.resumeBanner()
        val studioIndigo = ContextCompat.getColor(this, R.color.lumina_indigo)
        setupTopAppBar(binding.mainAppbar, topBarColor = studioIndigo)

        binding.apply {
            val isShowBrushSizeEnabled = config.showBrushSize
            brushSizeRow.beVisibleIf(isShowBrushSizeEnabled && !isEyeDropperOn && !isBucketFillOn)
            myCanvas.setAllowZooming(config.allowZoomingCanvas)
            myCanvas.setRelativeBrushSize(config.relativeBrushSize)
            strokeWidthBar.setColors(0, studioIndigo, 0)
        }

        if (config.preventPhoneFromSleeping) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requestedOrientation = when {
            config.forcePortraitMode -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        refreshMenuItems()
        updateBackgroundControls(config.canvasBackgroundColor)
        updateButtonStates()
    }

    override fun onPause() {
        adManager.pauseBanner()
        super.onPause()
        config.brushColor = color
        config.brushSize = brushSize
        if (config.preventPhoneFromSleeping) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        adManager.destroy()
        super.onDestroy()
        binding.myCanvas.mListener = null
    }

    private fun setupQuickColorSwatches() {
        val density = resources.displayMetrics.density
        val sizePx = (28 * density).toInt()
        val marginPx = (5 * density).toInt()
        binding.colorSwatchesContainer.removeAllViews()
        swatchViews.clear()

        STUDIO_SWATCH_COLORS.forEach { swatchColor ->
            val swatchView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginStart = marginPx
                    marginEnd = marginPx
                }
                setOnClickListener {
                    if (isEyeDropperOn) eyeDropperClicked()
                    setColor(swatchColor)
                }
            }
            swatchViews.add(swatchColor to swatchView)
            binding.colorSwatchesContainer.addView(swatchView)
        }
        refreshSwatchIndicators()
    }

    private fun refreshSwatchIndicators() {
        val density = resources.displayMetrics.density
        val isDarkCanvas = ColorUtils.calculateLuminance(config.canvasBackgroundColor) < 0.4
        val neutralStroke = if (isDarkCanvas) 0x55FFFFFF else 0x331E1B4B
        val selectedStroke = ContextCompat.getColor(this, R.color.lumina_coral)

        swatchViews.forEach { (swatchColor, view) ->
            val isSelected = !isEraserOn && swatchColor == color
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatchColor)
                val strokeWidth = if (isSelected) (3f * density).toInt() else (1.5f * density).toInt()
                setStroke(strokeWidth, if (isSelected) selectedStroke else neutralStroke)
            }
            view.background = drawable
            view.scaleX = if (isSelected) 1.14f else 1.0f
            view.scaleY = if (isSelected) 1.14f else 1.0f
        }
    }

    private fun refreshMenuItems() {
        binding.mainToolbar.menu.apply {
            findItem(R.id.menu_confirm).isVisible = isImageCaptureIntent || isEditIntent
            findItem(R.id.menu_save).isVisible = !isImageCaptureIntent && !isEditIntent
            findItem(R.id.menu_share).isVisible = !isImageCaptureIntent && !isEditIntent
            findItem(R.id.open_file).isVisible = !isEditIntent
        }
    }

    private fun setupOptionsMenu() {
        binding.mainToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_confirm -> confirmImage()
                R.id.menu_save -> trySaveImage()
                R.id.menu_share -> shareImage()
                R.id.clear -> displayClearCanvasPrompt()
                R.id.open_file -> tryOpenFile()
                R.id.change_background -> changeBackgroundClicked()
                R.id.menu_print -> printImage()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    override fun onBackPressedCompat(): Boolean {
        val hasUnsavedChanges = binding.myCanvas.hasPendingEdits() ||
                savedPathsHash != binding.myCanvas.getDrawingHashCode()
        if (hasUnsavedChanges && System.currentTimeMillis() - lastSavePromptTS > SAVE_DISCARD_PROMPT_INTERVAL) {
            lastSavePromptTS = System.currentTimeMillis()
            ConfirmationAdvancedDialog(
                activity = this,
                message = "",
                messageId = R.string.save_before_closing,
                positive = R.string.save,
                negative = R.string.discard
            ) {
                if (it) {
                    trySaveImage()
                } else {
                    performDefaultBack()
                }
            }
            return true
        }
        return false
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val message = getString(R.string.about_lumina_message, BuildConfig.VERSION_NAME)
        getAlertDialogBuilder()
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK) return
        val uri = resultData?.data ?: return

        when (requestCode) {
            PICK_IMAGE_INTENT -> tryOpenUri(uri, resultData)
            SAVE_IMAGE_INTENT -> {
                val outputStream = contentResolver.openOutputStream(uri)
                if (defaultExtension == SVG) {
                    Svg.saveToOutputStream(this, outputStream, binding.myCanvas) {
                        savedPathsHash = it
                        adManager.showInterstitialIfReady(this)
                    }
                } else {
                    saveToOutputStream(outputStream, defaultExtension.getCompressionFormat(), false) {
                        savedPathsHash = it
                        toast(R.string.file_saved)
                        adManager.showInterstitialIfReady(this)
                    }
                }
            }
        }
    }

    private fun tryOpenFile() {
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"

            try {
                startActivityForResult(this, PICK_IMAGE_INTENT)
            } catch (e: ActivityNotFoundException) {
                toast(R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun checkIntents() {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            tryOpenUri(uri!!, intent)
        }

        if (intent?.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true) {
            val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            imageUris.any { tryOpenUri(it, intent) }
        }

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            tryOpenUri(intent.data!!, intent)
        }

        if (intent?.action == MediaStore.ACTION_IMAGE_CAPTURE) {
            val output = intent.extras?.get(MediaStore.EXTRA_OUTPUT)
            if (output != null && output is Uri) {
                isImageCaptureIntent = true
                intentUri = output
                defaultPath = output.path!!
                refreshMenuItems()
            }
        }

        if (intent?.action == Intent.ACTION_EDIT) {
            val data = intent.data
            val output = intent.extras?.get(MediaStore.EXTRA_OUTPUT)
            if (data != null) {
                tryOpenUri(data, intent)
                isEditIntent = true
                if (output != null && output is Uri) {
                    intentUri = output
                }
            }
        }
    }

    private fun getStoragePermission(callback: () -> Unit) {
        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                callback()
            } else {
                toast(R.string.no_storage_permissions)
            }
        }
    }

    private fun tryOpenUri(uri: Uri, intent: Intent) = when {
        uri.scheme == "file" -> {
            uriToLoad = uri
            openPath(uri.path!!)
        }

        uri.scheme == "content" -> {
            uriToLoad = uri
            openUri(uri, intent)
        }

        else -> false
    }

    private fun openPath(path: String) = when {
        path.endsWith(".svg") -> {
            Svg.loadSvg(this, File(path), binding.myCanvas)
            defaultExtension = SVG
            true
        }

        File(path).isImageSlow() -> {
            lastBitmapPath = path
            binding.myCanvas.drawBitmap(this, path)
            defaultExtension = JPG
            true
        }

        else -> {
            toast(R.string.invalid_file_format)
            false
        }
    }

    private fun openUri(uri: Uri, intent: Intent): Boolean {
        val mime = MimeTypeMap.getSingleton()
        val type = mime.getExtensionFromMimeType(contentResolver.getType(uri))
            ?: intent.type ?: contentResolver.getType(uri)
        return when (type) {
            "svg", "image/svg+xml" -> {
                Svg.loadSvg(this, uri, binding.myCanvas)
                defaultExtension = SVG
                true
            }

            "jpg", "jpeg", "png", "gif", "image/jpg", "image/png", "image/gif", "webp" -> {
                binding.myCanvas.drawBitmap(this, uri)
                defaultExtension = JPG
                true
            }

            else -> {
                toast(R.string.invalid_file_format)
                false
            }
        }
    }

    private fun activateBrushTool() {
        if (isEraserOn) {
            isEraserOn = false
            binding.myCanvas.toggleEraser(false)
        }
        if (isEyeDropperOn) {
            isEyeDropperOn = false
            eyeDropper.stop()
        }
        if (isBucketFillOn) {
            isBucketFillOn = false
            binding.myCanvas.toggleBucketFill(false)
        }
        updateButtonStates()
    }

    private fun eraserClicked() {
        if (isEyeDropperOn) {
            eyeDropperClicked()
        } else if (isBucketFillOn) {
            bucketFillClicked()
        }

        isEraserOn = !isEraserOn
        updateEraserState()
    }

    private fun updateEraserState() {
        updateButtonStates()
        binding.myCanvas.toggleEraser(isEraserOn)
    }

    private fun changeBackgroundClicked() {
        val oldColor = (binding.myCanvas.background as ColorDrawable).color
        ColorPickerDialog(this, oldColor) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                config.canvasBackgroundColor = color
                setBackgroundColor(color)

                if (uriToLoad != null) {
                    tryOpenUri(uriToLoad!!, intent)
                }
            }
        }
    }

    private fun eyeDropperClicked() {
        if (isEraserOn) {
            eraserClicked()
        } else if (isBucketFillOn) {
            bucketFillClicked()
        }

        isEyeDropperOn = !isEyeDropperOn
        if (isEyeDropperOn) {
            eyeDropper.start()
        } else {
            eyeDropper.stop()
        }

        updateButtonStates()
    }

    private fun bucketFillClicked() {
        if (isEraserOn) {
            eraserClicked()
        } else if (isEyeDropperOn) {
            eyeDropperClicked()
        }

        isBucketFillOn = !isBucketFillOn

        updateButtonStates()
        binding.myCanvas.toggleBucketFill(isBucketFillOn)
    }

    private fun updateButtonStates() {
        if (config.showBrushSize) {
            hideBrushSettings(isEyeDropperOn || isBucketFillOn)
        }

        val isBrushActive = !isEraserOn && !isEyeDropperOn && !isBucketFillOn
        binding.apply {
            updateToolVisual(brushTool, isBrushActive)
            updateToolVisual(eraser, isEraserOn)
            updateToolVisual(eyeDropper, isEyeDropperOn)
            updateToolVisual(bucketFill, isBucketFillOn)
        }
        refreshSwatchIndicators()
    }

    private fun updateToolVisual(view: ImageView, enabled: Boolean) {
        val isDarkCanvas = ColorUtils.calculateLuminance(config.canvasBackgroundColor) < 0.4
        val activeColor = ContextCompat.getColor(this, R.color.lumina_coral)
        val inactiveColor = if (isDarkCanvas) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_charcoal)

        view.applyColorFilter(if (enabled) activeColor else inactiveColor)
        if (enabled) {
            view.setBackgroundResource(R.drawable.bg_studio_tool_active)
        } else {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun hideBrushSettings(hide: Boolean) {
        binding.brushSizeRow.beGoneIf(hide)
    }

    private fun confirmImage() {
        when {
            isEditIntent && intentUri != null -> {
                try {
                    val outputStream = contentResolver.openOutputStream(intentUri!!)
                    saveToOutputStream(outputStream, defaultPath.getCompressionFormat(), true)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            isEditIntent -> trySaveImage()

            intentUri?.scheme == "content" -> {
                val outputStream = contentResolver.openOutputStream(intentUri!!)
                saveToOutputStream(outputStream, defaultPath.getCompressionFormat(), true)
            }

            else -> handlePermission(PERMISSION_WRITE_STORAGE) {
                val fileDirItem = FileDirItem(defaultPath, defaultPath.getFilenameFromPath())
                getFileOutputStream(fileDirItem, true) {
                    saveToOutputStream(it, defaultPath.getCompressionFormat(), true)
                }
            }
        }
    }

    private fun saveToOutputStream(
        outputStream: OutputStream?,
        format: Bitmap.CompressFormat,
        finishAfterSaving: Boolean,
        onSaved: (Long) -> Unit = {}
    ) {
        if (outputStream == null) {
            toast(R.string.unknown_error_occurred)
            return
        }

        val quality = if (format == Bitmap.CompressFormat.PNG) {
            100
        } else {
            70
        }

        binding.myCanvas.whenDrawingReady {
            try {
                outputStream.use {
                    if (!binding.myCanvas.getBitmap(false).compress(format, quality, it)) {
                        throw IOException("Failed to encode drawing")
                    }
                }
                onSaved(binding.myCanvas.getDrawingHashCode())
                if (finishAfterSaving) {
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: IOException) {
                showErrorToast(e)
            }
        }
    }

    private fun trySaveImage() {
        if (isQPlus()) {
            SaveImageDialog(
                activity = this,
                defaultPath = defaultPath,
                defaultFilename = defaultFilename,
                defaultExtension = defaultExtension,
                hidePath = true
            ) { _, filename, extension ->
                val mimetype = if (extension == SVG) "svg+xml" else extension

                defaultFilename = filename
                defaultExtension = extension
                config.lastSaveExtension = extension

                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "image/$mimetype"
                    putExtra(Intent.EXTRA_TITLE, "$filename.$extension")
                    addCategory(Intent.CATEGORY_OPENABLE)

                    try {
                        startActivityForResult(this, SAVE_IMAGE_INTENT)
                    } catch (e: ActivityNotFoundException) {
                        toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        } else {
            getStoragePermission {
                saveImage()
            }
        }
    }

    private fun saveImage() {
        SaveImageDialog(
            activity = this,
            defaultPath = defaultPath,
            defaultFilename = defaultFilename,
            defaultExtension = defaultExtension,
            hidePath = false
        ) { fullPath, filename, extension ->
            saveFile(fullPath)
            defaultPath = fullPath.getParentPath()
            defaultFilename = filename
            defaultExtension = extension
            config.lastSaveFolder = defaultPath
            config.lastSaveExtension = extension
        }
    }

    private fun saveFile(path: String) {
        val onSaved: (Long) -> Unit = {
            savedPathsHash = it
            rescanPaths(arrayListOf(path)) {}
            adManager.showInterstitialIfReady(this)
        }
        when (path.getFilenameExtension()) {
            SVG -> Svg.saveSvg(this, path, binding.myCanvas, onSaved)
            else -> saveImageFile(path, onSaved)
        }
    }

    private fun saveImageFile(path: String, onSaved: (Long) -> Unit) {
        val fileDirItem = FileDirItem(path, path.getFilenameFromPath())
        getFileOutputStream(fileDirItem, true) {
            if (it != null) {
                saveToOutputStream(it, path.getCompressionFormat(), false) { hash ->
                    onSaved(hash)
                    toast(R.string.file_saved)
                }
            } else {
                toast(R.string.unknown_error_occurred)
            }
        }
    }

    private fun shareImage() {
        binding.myCanvas.whenDrawingReady {
            getImagePath(binding.myCanvas.getBitmap(false)) {
                if (it != null) {
                    sharePathIntent(it, BuildConfig.APPLICATION_ID)
                } else {
                    toast(R.string.unknown_error_occurred)
                }
            }
        }
    }

    private fun getImagePath(bitmap: Bitmap, callback: (path: String?) -> Unit) {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 0, bytes)

        val folder = File(cacheDir, FOLDER_NAME)
        if (!folder.exists()) {
            if (!folder.mkdir()) {
                callback(null)
                return
            }
        }

        val newPath = "$folder/$FILE_NAME"
        val fileDirItem = FileDirItem(newPath, FILE_NAME)
        getFileOutputStream(fileDirItem, true) {
            if (it != null) {
                try {
                    it.write(bytes.toByteArray())
                    callback(newPath)
                } catch (_: Exception) {
                } finally {
                    it.close()
                }
            } else {
                callback("")
            }
        }
    }

    private fun displayClearCanvasPrompt() {
        if (hasContent) {
            ConfirmationDialog(this, messageId = R.string.clear_canvas_confirmation) {
                clearCanvas()
                adManager.showInterstitialIfReady(this)
            }
        } else {
            clearCanvas()
        }
    }

    private fun clearCanvas() {
        uriToLoad = null
        binding.myCanvas.clearCanvas()
        defaultExtension = PNG
        defaultPath = ""
        lastBitmapPath = ""
    }

    private fun pickColor() {
        ColorPickerDialog(this, color) { wasPositivePressed, picked ->
            if (wasPositivePressed) {
                if (isEyeDropperOn) {
                    eyeDropperClicked()
                }

                setColor(picked)
            }
        }
    }

    fun setBackgroundColor(pickedColor: Int) {
        updateBackgroundControls(pickedColor)
        binding.myCanvas.updateBackgroundColor(pickedColor)
        defaultExtension = PNG
    }

    fun updateBackgroundControls(pickedColor: Int) {
        if (isEyeDropperOn) {
            eyeDropperClicked()
        }

        val isDarkCanvas = ColorUtils.calculateLuminance(pickedColor) < 0.4
        val pillBgColor = ContextCompat.getColor(
            this,
            if (isDarkCanvas) R.color.lumina_pill_dark else R.color.lumina_pill_light
        )
        val pillStrokeColor = ContextCompat.getColor(
            this,
            if (isDarkCanvas) R.color.lumina_pill_stroke_dark else R.color.lumina_pill_stroke_light
        )
        val iconTint = if (isDarkCanvas) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_charcoal)
        val radiusPx = 26f * resources.displayMetrics.density
        val strokePx = (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        fun createPillBackground() = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(pillBgColor)
            setStroke(strokePx, pillStrokeColor)
        }

        binding.apply {
            historyPill.background = createPillBackground()
            toolsPill.background = createPillBackground()
            studioBottomDock.background = createPillBackground()

            undo.applyColorFilter(iconTint)
            redo.applyColorFilter(iconTint)
            strokeWidthLabel.setTextColor(iconTint)
            strokeWidthBar.setColors(
                0,
                ContextCompat.getColor(this@MainActivity, if (isDarkCanvas) R.color.lumina_coral else R.color.lumina_indigo),
                0
            )
            getBrushPreviewView().setStroke(getBrushStrokeSize(), iconTint)
        }

        updateButtonStates()
    }

    private fun setColor(pickedColor: Int) {
        color = pickedColor
        binding.colorPicker.setFillWithStroke(color, config.canvasBackgroundColor, true)
        binding.myCanvas.setColor(color)
        isEraserOn = false
        updateEraserState()
        getBrushPreviewView().setColor(color)
    }

    private fun getBrushPreviewView() = binding.strokeWidthPreview.background as GradientDrawable

    private fun getBrushStrokeSize() =
        resources.getDimension(R.dimen.preview_dot_stroke_size).toInt()

    override fun toggleUndoVisibility(visible: Boolean) {
        canUndo = visible
        binding.undo.beVisibleIf(visible)
        binding.historyPill.beVisibleIf(canUndo || canRedo)
    }

    override fun toggleRedoVisibility(visible: Boolean) {
        canRedo = visible
        binding.redo.beVisibleIf(visible)
        binding.historyPill.beVisibleIf(canUndo || canRedo)
    }

    override fun toggleHasContent(hasContent: Boolean) {
        this.hasContent = hasContent
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(BITMAP_PATH, lastBitmapPath)
        outState.putBoolean(HAS_CONTENT, hasContent)
        if (uriToLoad != null) {
            outState.putString(URI_TO_LOAD, uriToLoad.toString())
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        lastBitmapPath = savedInstanceState.getString(BITMAP_PATH)!!
        hasContent = savedInstanceState.getBoolean(HAS_CONTENT, false)
        if (lastBitmapPath.isNotEmpty()) {
            openPath(lastBitmapPath)
        } else if (savedInstanceState.containsKey(URI_TO_LOAD)) {
            uriToLoad = Uri.parse(savedInstanceState.getString(URI_TO_LOAD))
            tryOpenUri(uriToLoad!!, intent)
        }
    }

    private fun updateBrushSize() {
        binding.apply {
            myCanvas.setBrushSize(brushSize)
            val scale = max(0.15f, brushSize / 100f)
            strokeWidthPreview.scaleX = scale
            strokeWidthPreview.scaleY = scale
            strokeWidthLabel.text = brushSize.toInt().toString()
        }
    }

    private fun printImage() {
        val printHelper = PrintHelper(this)
        printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT
        binding.myCanvas.whenDrawingReady {
            try {
                printHelper.printBitmap(getString(R.string.app_name), binding.myCanvas.getBitmap(false))
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}
