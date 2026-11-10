package com.typefree.ime.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ShareCardTheme(
    val label: String,
    val bgTopColorInt: Int,
    val bgBottomColorInt: Int,
    val cardSurfaceColorInt: Int,
    val cardBorderColorInt: Int,
    val primaryTextColorInt: Int,
    val mutedTextColorInt: Int,
    val accentColorInt: Int,
    val badgeBgColorInt: Int
) {
    WARM_PAPER(
        label = "📜 暖白纸感",
        bgTopColorInt = 0xFFFBF8F1.toInt(),
        bgBottomColorInt = 0xFFF3ECE0.toInt(),
        cardSurfaceColorInt = 0xFFFFFDF9.toInt(),
        cardBorderColorInt = 0xFFE6DEC8.toInt(),
        primaryTextColorInt = 0xFF1F2328.toInt(),
        mutedTextColorInt = 0xFF6B7280.toInt(),
        accentColorInt = 0xFFD97706.toInt(),
        badgeBgColorInt = 0xFFFEF3C7.toInt()
    ),
    OBSIDIAN_DARK(
        label = "🌙 极客暗金",
        bgTopColorInt = 0xFF0F1115.toInt(),
        bgBottomColorInt = 0xFF171A21.toInt(),
        cardSurfaceColorInt = 0xFF1A1E26.toInt(),
        cardBorderColorInt = 0xFF2D3340.toInt(),
        primaryTextColorInt = 0xFFF3F4F6.toInt(),
        mutedTextColorInt = 0xFF9CA3AF.toInt(),
        accentColorInt = 0xFFF59E0B.toInt(),
        badgeBgColorInt = 0xFF2B2418.toInt()
    ),
    EMERALD_NOTE(
        label = "🌿 翡翠便签",
        bgTopColorInt = 0xFFECFDF5.toInt(),
        bgBottomColorInt = 0xFFD1FAE5.toInt(),
        cardSurfaceColorInt = 0xFFFFFFFF.toInt(),
        cardBorderColorInt = 0xFFA7F3D0.toInt(),
        primaryTextColorInt = 0xFF064E3B.toInt(),
        mutedTextColorInt = 0xFF047857.toInt(),
        accentColorInt = 0xFF10B981.toInt(),
        badgeBgColorInt = 0xFFD1FAE5.toInt()
    )
}

object ShareCardRenderer {
    const val CARD_WIDTH_PX = 1080
    const val MAX_CARD_TEXT_CHARS = 1200
    const val MAX_BITMAP_HEIGHT_PX = 4200
    const val MAX_CACHED_SHARE_CARDS = 10
    const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L

    /**
     * Pure helper for truncating ultra-long text before rendering to prevent OOM
     * and oversized bitmaps. Returns `(displayText, omittedCharCount)`.
     */
    fun truncateCardBodyText(
        text: String,
        maxChars: Int = MAX_CARD_TEXT_CHARS
    ): Pair<String, Int> {
        val normalized = text
            .replace("\r\n", "\n")
            .trim()
        if (normalized.isEmpty()) {
            return "（空便签）" to 0
        }
        if (normalized.length <= maxChars) {
            return normalized to 0
        }
        val omitted = normalized.length - maxChars
        val truncated = normalized.take(maxChars).trimEnd() + "\n\n…（余下 $omitted 字已省略）"
        return truncated to omitted
    }

    /**
     * Cleans expired (> [maxAgeMs]) or excess (> [maxFiles]) temporary share card PNGs
     * inside `cacheDir/share_cards/`. Returns the number of deleted files.
     */
    fun cleanOldShareCards(
        dir: File,
        nowMs: Long = System.currentTimeMillis(),
        maxFiles: Int = MAX_CACHED_SHARE_CARDS,
        maxAgeMs: Long = MAX_CACHE_AGE_MS
    ): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        val pngFiles = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".png", ignoreCase = true)
        }?.toList().orEmpty()
        if (pngFiles.isEmpty()) return 0

        var deletedCount = 0
        val freshFiles = mutableListOf<File>()
        for (file in pngFiles) {
            val ageMs = nowMs - file.lastModified()
            if (ageMs > maxAgeMs) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deletedCount++
                }
            } else {
                freshFiles.add(file)
            }
        }

        val sortedNewestFirst = freshFiles.sortedByDescending { it.lastModified() }
        if (sortedNewestFirst.size > maxFiles) {
            for (excess in sortedNewestFirst.drop(maxFiles)) {
                if (runCatching { excess.delete() }.getOrDefault(false)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    fun formatCurrentTimestamp(timeMs: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy.MM.dd · HH:mm", Locale.getDefault())
        return sdf.format(Date(timeMs))
    }

    /**
     * Renders a high-resolution `1080px` width share card `Bitmap` offscreen using Android native
     * `Canvas` and `StaticLayout` (safe for multi-paragraph text, bullet lists, and emoji).
     */
    fun renderCardBitmap(
        text: String,
        theme: ShareCardTheme,
        timestampLabel: String = formatCurrentTimestamp(),
        badgeLabel: String = "TypeFree · 闪念便签",
        showWatermark: Boolean = true
    ): Bitmap {
        val (displayText, _) = truncateCardBodyText(text, MAX_CARD_TEXT_CHARS)

        val outerMargin = 68f
        val innerPadH = 68f
        val innerPadTop = 68f
        val innerPadBottom = if (showWatermark) 64f else 56f
        val headerBlockHeight = 96f
        val footerBlockHeight = if (showWatermark) 84f else 0f

        val contentWidth = (CARD_WIDTH_PX - (outerMargin + innerPadH) * 2f).toInt().coerceAtLeast(400)

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryTextColorInt
            textSize = 40f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val staticLayout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, bodyPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(16f, 1.18f)
            .setIncludePad(true)
            .build()

        val rawTextHeight = staticLayout.height.toFloat().coerceAtLeast(160f)
        val rawTotalHeight = (
            outerMargin * 2f +
                innerPadTop +
                headerBlockHeight +
                rawTextHeight +
                footerBlockHeight +
                innerPadBottom
            ).toInt()

        val totalHeight = rawTotalHeight.coerceIn(560, MAX_BITMAP_HEIGHT_PX)
        val bitmap = Bitmap.createBitmap(CARD_WIDTH_PX, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Outer Background Gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                CARD_WIDTH_PX.toFloat(),
                totalHeight.toFloat(),
                theme.bgTopColorInt,
                theme.bgBottomColorInt,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH_PX.toFloat(), totalHeight.toFloat(), bgPaint)

        // 2. Elevated Inner Card Surface & Border
        val cardRect = RectF(
            outerMargin,
            outerMargin,
            CARD_WIDTH_PX - outerMargin,
            totalHeight - outerMargin
        )
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardSurfaceColorInt
            setShadowLayer(28f, 0f, 12f, 0x22000000)
        }
        canvas.drawRoundRect(cardRect, 44f, 44f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardBorderColorInt
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(cardRect, 44f, 44f, borderPaint)

        // 3. Top Accent Bar & Header Badge + Timestamp
        val contentLeft = outerMargin + innerPadH
        val contentRight = CARD_WIDTH_PX - outerMargin - innerPadH
        val headerTop = outerMargin + innerPadTop

        val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accentColorInt
            textSize = 27f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val badgeTextWidth = badgeTextPaint.measureText(badgeLabel)
        val badgeRect = RectF(
            contentLeft,
            headerTop,
            contentLeft + badgeTextWidth + 44f,
            headerTop + 54f
        )
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.badgeBgColorInt
        }
        canvas.drawRoundRect(badgeRect, 27f, 27f, badgeBgPaint)
        canvas.drawText(
            badgeLabel,
            contentLeft + 22f,
            headerTop + 36.5f,
            badgeTextPaint
        )

        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.mutedTextColorInt
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            timestampLabel,
            contentRight,
            headerTop + 36.5f,
            timePaint
        )

        // Subtle divider line below header
        val dividerY = headerTop + 74f
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardBorderColorInt
            strokeWidth = 2f
        }
        canvas.drawLine(contentLeft, dividerY, contentRight, dividerY, dividerPaint)

        // 4. Body Text Layout (clipped safely inside card body region)
        val bodyTop = headerTop + headerBlockHeight
        val bodyBottomLimit = totalHeight - outerMargin - innerPadBottom - footerBlockHeight
        canvas.save()
        canvas.clipRect(contentLeft, bodyTop, contentRight, bodyBottomLimit)
        canvas.translate(contentLeft, bodyTop)
        staticLayout.draw(canvas)
        canvas.restore()

        // 5. Optional Brand Watermark Footer (`Recorded via TypeFree`)
        if (showWatermark) {
            val footerDividerY = totalHeight - outerMargin - innerPadBottom - 44f
            canvas.drawLine(contentLeft, footerDividerY, contentRight, footerDividerY, dividerPaint)

            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.accentColorInt
            }
            val footerBaselineY = totalHeight - outerMargin - innerPadBottom + 4f
            canvas.drawCircle(contentLeft + 10f, footerBaselineY - 9f, 8f, dotPaint)

            val wmPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.mutedTextColorInt
                textSize = 25f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText(
                "Recorded via TypeFree",
                contentLeft + 28f,
                footerBaselineY,
                wmPaint
            )

            val sloganPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.mutedTextColorInt
                textSize = 23f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(
                "AI Voice & Rime Studio",
                contentRight,
                footerBaselineY,
                sloganPaint
            )
        }

        return bitmap
    }

    fun writeShareCardToCacheFile(context: Context, bitmap: Bitmap): File {
        val shareDir = File(context.cacheDir, "share_cards")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
        }
        cleanOldShareCards(shareDir, maxFiles = MAX_CACHED_SHARE_CARDS - 1)
        val outFile = File(shareDir, "typefree_note_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
        }
        return outFile
    }

    fun saveCardToGallery(context: Context, bitmap: Bitmap): Result<Uri> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val cacheFile = writeShareCardToCacheFile(context, bitmap)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            shareCardImage(context, bitmap).getOrThrow()
            return@runCatching uri
        }
        val fileName = "TypeFree_Note_${System.currentTimeMillis()}.png"
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/TypeFree"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("无法创建相册图片条目")
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("写入 PNG 失败")
            }
        } ?: throw IllegalStateException("无法打开相册输出流")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
        }
        uri
    }

    fun copyCardImageToClipboard(context: Context, bitmap: Bitmap): Result<Uri> = runCatching {
        val cacheFile = writeShareCardToCacheFile(context, bitmap)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("系统剪贴板服务不可用")
        val clip = ClipData.newUri(context.contentResolver, "TypeFree 便签卡片", uri)
        clipboard.setPrimaryClip(clip)
        uri
    }

    fun shareCardImage(context: Context, bitmap: Bitmap): Result<Unit> = runCatching {
        val cacheFile = writeShareCardToCacheFile(context, bitmap)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("TypeFree 便签卡片", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "分享 TypeFree 便签卡片").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
