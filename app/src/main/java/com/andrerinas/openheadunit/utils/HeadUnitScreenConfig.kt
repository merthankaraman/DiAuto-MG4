package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.decoder.VideoDecoder
import kotlin.math.roundToInt

object HeadUnitScreenConfig {

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var density: Float = 1.0f
    private var densityDpi: Int = 240
    private var scaleFactor: Float = 1.0f
    private var isSmallScreen: Boolean = true
    private var isPortraitScaled: Boolean = false
    private var isInitialized: Boolean = false
    private var lastSettingsHash: Int = 0
    
    // Flag to determine if the projection should stretch and ignore aspect ratio
    private var stretchToFill: Boolean = false 
    
    // Forced scale for older devices (Legacy fix)
    var forcedScale: Boolean = false
        private set

    var negotiatedResolutionType: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
    var isResolutionLocked: Boolean = false
        private set

    private lateinit var currentSettings: Settings // Store settings instance

    // System Insets (Bars/Cutouts)
    var systemInsetLeft: Int = 0
        private set
    var systemInsetTop: Int = 0
        private set
    var systemInsetRight: Int = 0
        private set
    var systemInsetBottom: Int = 0
        private set

    // Raw Screen Dimensions (Full Display)
    private var realScreenWidthPx: Int = 0
    private var realScreenHeightPx: Int = 0


    fun init(context: Context, displayMetrics: DisplayMetrics, settings: Settings) {
        stretchToFill = settings.stretchToFill
        forcedScale = settings.forcedScale && settings.viewMode == Settings.ViewMode.SURFACE

        val realW: Int
        val realH: Int
        val usableW: Int
        val usableH: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            val windowManager = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            // On API 30+, bounds on an Activity context often return the usable area.
            // We use the displayMetrics as a fallback for the physical area.
            //
            // DiLink 5.1 caveat: BYD's "byd-freeform" windowing mode clamps the activity's
            // appBounds (and therefore displayMetrics) to 1920x984 while the window frame
            // (currentWindowMetrics.bounds) really is the full 1920x1080 with both system
            // bars hidden. Taking displayMetrics at face value makes the "real" screen
            // 96px shorter than the view BydFullscreen actually draws into, which shows up
            // as a vertically stretched picture plus a growing touch offset down the screen.
            // The real area can never be smaller than the window we are given, so clamp it up.
            realW = Math.max(displayMetrics.widthPixels, bounds.width())
            realH = Math.max(displayMetrics.heightPixels, bounds.height())
            usableW = bounds.width()
            usableH = bounds.height()
        } else { // Older APIs
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            realW = size.x
            realH = size.y
            
            @Suppress("DEPRECATION")
            display.getSize(size)
            usableW = size.x
            usableH = size.y
        }

        val finalRealW: Int
        val finalRealH: Int
        val finalUsableW: Int
        val finalUsableH: Int

        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.LANDSCAPE || 
            screenOrientation == Settings.ScreenOrientation.LANDSCAPE_REVERSE) {
            finalRealW = Math.max(realW, realH)
            finalRealH = Math.min(realW, realH)
            finalUsableW = Math.max(usableW, usableH)
            finalUsableH = Math.min(usableW, usableH)
        } else if (screenOrientation == Settings.ScreenOrientation.PORTRAIT || 
                   screenOrientation == Settings.ScreenOrientation.PORTRAIT_REVERSE) {
            finalRealW = Math.min(realW, realH)
            finalRealH = Math.max(realW, realH)
            finalUsableW = Math.min(usableW, usableH)
            finalUsableH = Math.max(usableW, usableH)
        } else {
            finalRealW = realW
            finalRealH = realH
            finalUsableW = usableW
            finalUsableH = usableH
        }

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Raw size: ${realW}x${realH}, usable: ${usableW}x${usableH}, orientation setting: $screenOrientation")
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Final normalized size: ${finalRealW}x${finalRealH}, usable: ${finalUsableW}x${finalUsableH}")

        // Only update if dimensions or settings changed
        val currentHash = computeSettingsHash(settings)
        if (isInitialized && realScreenWidthPx == finalRealW && realScreenHeightPx == finalRealH && lastSettingsHash == currentHash) {
            return
        }

        // If settings changed (e.g. orientation swap), unlock resolution before recalculating
        if (isInitialized && lastSettingsHash != 0 && lastSettingsHash != currentHash) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Settings changed ($lastSettingsHash -> $currentHash). Unlocking resolution.")
            unlockResolution()
        }

        isInitialized = true
        lastSettingsHash = currentHash
        currentSettings = settings

        // Full immersive (both bars hidden) or partial (top-only / left-only): the window is
        // edge-to-edge, so the anchor is the physical screen. Remaining visible bars are then
        // subtracted via updateInsets(). Using "usable" here for STATUS_ONLY left a short
        // height even after the top bar was gone, so AA looked cropped from the top.
        val immersive = settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE ||
                        settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH
        val edgeToEdge = immersive ||
            settings.fullscreenMode == Settings.FullscreenMode.STATUS_ONLY ||
            settings.fullscreenMode == Settings.FullscreenMode.NAV_ONLY

        // THE ANCHOR:
        // Edge-to-edge modes: physical screen. Normal: usable window (bars already baked in).
        val defaultAnchorW = if (edgeToEdge) finalRealW else finalUsableW
        val defaultAnchorH = if (edgeToEdge) finalRealH else finalUsableH
        
        density = displayMetrics.density
        densityDpi = displayMetrics.densityDpi

        // Initial Insets: For non-immersive, the bars are already baked into the anchor (realSize = 736),
        // so we start with 0 system insets and just add manual settings.
        // Seed MG4 OEM top chrome (~80px) early for hide-left-only so the first AA handshake
        // already uses ~1920×1000 (avoids a mid-session glitch when insets arrive late).
        systemInsetLeft = settings.insetLeft
        systemInsetTop = settings.insetTop + OemDisplayChrome.topBarReservePx(
            settings.fullscreenMode == Settings.FullscreenMode.NAV_ONLY
        )
        systemInsetRight = settings.insetRight
        systemInsetBottom = settings.insetBottom
        
        // Check if we have cached surface dimensions from a previous session.
        // If the settings haven't changed (same hash), use the cached values
        // to avoid a mid-session UpdateUiConfigRequest and potential flicker.
        val cachedW = settings.cachedSurfaceWidth
        val cachedH = settings.cachedSurfaceHeight
        val cachedHash = settings.cachedSurfaceSettingsHash

        if (cachedW > 0 && cachedH > 0 && cachedHash == currentHash) {
            // Cached surface dimensions are the usable area. The anchor includes insets.
            realScreenWidthPx = cachedW + systemInsetLeft + systemInsetRight
            realScreenHeightPx = cachedH + systemInsetTop + systemInsetBottom
            AppLog.i("[UI_DEBUG_FIX] HeadUnitScreenConfig: Using cached surface dimensions: ${cachedW}x${cachedH} (anchor: ${realScreenWidthPx}x${realScreenHeightPx})")
            // A cached surface recorded while the window was clamped (e.g. a DiLink session
            // before the fullscreen enforcer had run) must never shrink the immersive anchor
            // below the window we are actually drawing into - that reintroduces the stretch
            // and the touch offset. Self-heals a stale cache instead of requiring a wipe.
            if (edgeToEdge && (realScreenWidthPx < defaultAnchorW || realScreenHeightPx < defaultAnchorH)) {
                AppLog.i("[UI_DEBUG_FIX] HeadUnitScreenConfig: Stale cached anchor ${realScreenWidthPx}x${realScreenHeightPx} < window ${defaultAnchorW}x${defaultAnchorH}; using the window.")
                realScreenWidthPx = Math.max(realScreenWidthPx, defaultAnchorW)
                realScreenHeightPx = Math.max(realScreenHeightPx, defaultAnchorH)
            }
        } else {
            realScreenWidthPx = defaultAnchorW
            realScreenHeightPx = defaultAnchorH
            if (cachedW > 0) {
                AppLog.i("[UI_DEBUG_FIX] HeadUnitScreenConfig: Cache invalidated (hash mismatch: stored=$cachedHash, current=$currentHash)")
            }
        }

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Honest Init | Mode: ${settings.fullscreenMode} | Anchor: ${realScreenWidthPx}x${realScreenHeightPx} | Seeded Insets: L$systemInsetLeft T$systemInsetTop R$systemInsetRight B$systemInsetBottom")
        
        recalculate()
    }

    fun updateInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (systemInsetLeft == left && systemInsetTop == top && systemInsetRight == right && systemInsetBottom == bottom) {
            return
        }
        
        systemInsetLeft = left
        systemInsetTop = top
        systemInsetRight = right
        systemInsetBottom = bottom
        
        if (isInitialized) {
            recalculate()
        }
    }

    // Native standard resolution for a given panel size, mirroring the AUTO selection so the
    // resolution cap never advertises more than the panel warrants (issue #650).
    /** Map a resolution class to the proto type for the current orientation (shared by the manual
     * selection path and the panel cap, so both agree). */
    private fun protoForResolution(
        res: Settings.Resolution,
        portrait: Boolean
    ): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType {
        val landscape = res.codec
            ?: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
        if (!portrait) return landscape
        return when (res) {
            Settings.Resolution._800x480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            Settings.Resolution._1280x720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            Settings.Resolution._1920x1080 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
            Settings.Resolution._2560x1440 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
            Settings.Resolution._3840x2160 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2160x3840
            else -> landscape
        }
    }

    /**
     * The proto resolution the physical panel warrants, in the display's orientation. Delegates to
     * SystemOptimizer.panelCeiling (the shared source of truth) so the runtime cap agrees with the
     * settings "too high" warning and the DPI calculation (issue #767).
     */
    private fun autoResolutionForPanel(
        w: Int,
        h: Int,
        portrait: Boolean,
        canHevc: Boolean
    ): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType {
        return protoForResolution(SystemOptimizer.panelCeiling(w, h, canHevc), portrait)
    }

    private fun pixelsOf(type: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType): Long {
        val s = type.toString().replace("_", "")
        return try {
            val parts = s.split("x")
            parts[0].toLong() * parts[1].toLong()
        } catch (e: Exception) {
            0L
        }
    }

    private fun recalculate() {
        // Calculate USABLE area
        screenWidthPx = realScreenWidthPx - systemInsetLeft - systemInsetRight
        screenHeightPx = realScreenHeightPx - systemInsetTop - systemInsetBottom

        if (screenWidthPx <= 0 || screenHeightPx <= 0) {
            screenWidthPx = realScreenWidthPx
            screenHeightPx = realScreenHeightPx
        }

        val selectedResolution = Settings.Resolution.fromId(currentSettings.resolutionId)
        // Orientation for the AA stream must follow the physical panel, not the inset-shrunk
        // usable box. A wide left nav can make usable width < height and AUTO would then ask
        // for portrait 1080x1920 on a landscape HU — the picture looks destroyed.
        val isPortraitDisplay = realScreenHeightPx > realScreenWidthPx
        val canNegotiateHevc = canNegotiateHevcHighResolution()

        // 1. Determine base negotiated resolution
        if (isResolutionLocked) {
            // Safety Check: If the locked resolution's orientation (Landscape/Portrait) 
            // no longer matches the display orientation, the lock is stale and must be dropped.
            val isPortraitRes = getNegotiatedHeight() > getNegotiatedWidth()
            if (isPortraitRes != isPortraitDisplay) {
                AppLog.i("[UI_DEBUG] CarScreen: Orientation mismatch detected (Res: ${if(isPortraitRes) "P" else "L"}, Display: ${if(isPortraitDisplay) "P" else "L"}). DROPPING LOCK.")
                unlockResolution()
            } else {
                AppLog.i("[UI_DEBUG] CarScreen: RESOLUTION LOCKED to $negotiatedResolutionType. Usable area is ${screenWidthPx}x${screenHeightPx}. Skipping re-negotiation.")
            }
        }
        
        if (!isResolutionLocked && selectedResolution == Settings.Resolution.AUTO) {
            // Same bucket as the panel cap / settings "recommended" — never a second ladder on
            // usable pixels that can diverge (and blow past 1080p) after bar insets change.
            negotiatedResolutionType = autoResolutionForPanel(
                realScreenWidthPx, realScreenHeightPx, isPortraitDisplay, canNegotiateHevc
            )
        } else if (!isResolutionLocked) {
            // Manual selection: map to the correct orientation via the shared helper.
            negotiatedResolutionType = protoForResolution(
                selectedResolution ?: Settings.Resolution._800x480, isPortraitDisplay
            )
        }

        // Cap the negotiated resolution to what the physical panel warrants, so we never ask the
        // phone for more pixels than the screen can show. Downscaling e.g. 1080p to a 600p panel
        // every frame overloads the display scaler (MediaTek MDP) and stalls video (issue #650).
        // min(current, panelCeiling): only ever lowers, so explicit lower choices and HEVC-gated
        // 1440p/4K are never raised.
        val preCapResolution = negotiatedResolutionType
        val panelCeiling = autoResolutionForPanel(realScreenWidthPx, realScreenHeightPx, isPortraitDisplay, canNegotiateHevc)
        if (pixelsOf(negotiatedResolutionType) > pixelsOf(panelCeiling)) {
            negotiatedResolutionType = panelCeiling
        }
        AppLog.i(
            "[RES_CAP] resolutionId=${currentSettings.resolutionId} " +
                "realScreen=${realScreenWidthPx}x${realScreenHeightPx} usable=${screenWidthPx}x${screenHeightPx} " +
                "portrait=$isPortraitDisplay locked=$isResolutionLocked chosen=$preCapResolution " +
                "capped=$negotiatedResolutionType changed=${preCapResolution != negotiatedResolutionType}"
        )

        // 2. Perform scaling calculations (now safe because negotiatedResolutionType is set)
        AppLog.i("[UI_DEBUG] CarScreen: usable area ${screenWidthPx}x${screenHeightPx}, using $negotiatedResolutionType")

        // 1080p-class automotive panels (and slight OEM overscan) stay on the 1:1 path.
        // Crossing the old exact 1920x1080 line enabled scaleFactor and wrecked Auto.
        val longUsable = maxOf(screenWidthPx, screenHeightPx)
        val shortUsable = minOf(screenWidthPx, screenHeightPx)
        isSmallScreen = longUsable <= 1920 && shortUsable <= 1200

        scaleFactor = 1.0f
        // A stream narrower than the panel (720p on the MG4's 1920x640) is upscaled by the view, so
        // the margins must assume the same uniform scale or the phone lays out a taller picture.
        val upscaledStream = getNegotiatedWidth() in 1 until screenWidthPx
        if (!isSmallScreen || upscaledStream) {
            val sWidth = screenWidthPx.toFloat()
            val sHeight = screenHeightPx.toFloat()
            if (getNegotiatedWidth() > 0 && getNegotiatedHeight() > 0) {
                 if (sWidth / sHeight < getAspectRatio()) {
                    isPortraitScaled = true
                    scaleFactor = sHeight / getNegotiatedHeight().toFloat()
                } else {
                    isPortraitScaled = false
                    scaleFactor = sWidth / getNegotiatedWidth().toFloat()
                }
            }
        }
        
        AppLog.i("[UI_DEBUG] CarScreen isSmallScreen: $isSmallScreen, scaleFactor: $scaleFactor, margins: w=${getWidthMargin()}, h=${getHeightMargin()}")
    }

    fun getAdjustedHeight(): Int {
        return (getNegotiatedHeight() * scaleFactor).roundToInt()
    }

    fun getAdjustedWidth(): Int {
        return (getNegotiatedWidth() * scaleFactor).roundToInt()
    }

    private fun getAspectRatio(): Float {
        return getNegotiatedWidth().toFloat() / getNegotiatedHeight().toFloat()
    }

    fun getNegotiatedHeight(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return try {
            resString.split("x")[1].toInt()
        } catch (e: Exception) {
            480
        }
    }

    private fun canNegotiateHevcHighResolution(): Boolean {
        if (VideoDecoder.isHevcSupported()) return true
        if (currentSettings.videoCodec != VideoDecoder.CodecType.H265.settingsValue || !currentSettings.forceSoftwareDecoding) return false
        return when (currentSettings.softwareVideoDecoder) {
            Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> VideoDecoder.isBundledHevcDecoderAvailable()
            Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
        }
    }

    fun getNegotiatedWidth(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return try {
            resString.split("x")[0].toInt()
        } catch (e: Exception) {
            800
        }
    }

    fun getHeightMargin(): Int {
        val margin = ((getAdjustedHeight() - screenHeightPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    fun getWidthMargin(): Int {
        val margin = ((getAdjustedWidth() - screenWidthPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    private fun divideOrOne(numerator: Float, denominator: Float): Float {
        return if (denominator == 0.0f) 1.0f else numerator / denominator
    }

    fun getScaleX(): Float {
        if (forcedScale) {
            return 1.0f
        }

        if (getNegotiatedWidth() > screenWidthPx) {
            return divideOrOne(getNegotiatedWidth().toFloat(), screenWidthPx.toFloat())
        }
        if (isPortraitScaled) {
            return divideOrOne(getAspectRatio(), (screenWidthPx.toFloat() / screenHeightPx.toFloat()))
        }
        return 1.0f
    }
        // Stretch option PR #259
    fun getScaleY(): Float {
        if (forcedScale) {
            return 1.0f
        }

        if (getNegotiatedHeight() > screenHeightPx) {
            return if (stretchToFill) {
                // Before PR #233 Fix scaler Y
                divideOrOne(getNegotiatedHeight().toFloat(), screenHeightPx.toFloat())
            } else {
                // After PR #233 Fix scaler Y
                divideOrOne((screenWidthPx.toFloat() / screenHeightPx.toFloat()), getAspectRatio())
            }
        }

        if (isPortraitScaled) {
            return 1.0f
        }

        return divideOrOne((screenWidthPx.toFloat() / screenHeightPx.toFloat()), getAspectRatio())
    }

    fun getDensityDpi(): Int {
        if (this::currentSettings.isInitialized && currentSettings.dpiPixelDensity != 0) {
            return currentSettings.dpiPixelDensity
        }
        // Auto DPI: match stock AllGo EH32 densities per negotiated resolution.
        return when (negotiatedResolutionType) {
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920 -> 227
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480 -> 151
            else -> densityDpi
        }
    }

    fun getPixelAspectRatioE4(): Int {
        return if (this::currentSettings.isInitialized && currentSettings.pixelAspectRatioE4 > 0) {
            currentSettings.pixelAspectRatioE4
        } else {
            10000 // 1.0 = square pixels
        }
    }

    fun getUsableWidth(): Int = screenWidthPx
    fun getUsableHeight(): Int = screenHeightPx

    // These are half the total margin, distributed symmetrically.
    fun getLeftMargin(): Int = getWidthMargin() / 2
    fun getRightMargin(): Int = getWidthMargin() - getLeftMargin()
    fun getTopMargin(): Int = getHeightMargin() / 2
    fun getBottomMargin(): Int = getHeightMargin() - getTopMargin()

    /**
     * Called when the actual rendering surface dimensions become known (from onSurfaceChanged).
     * Compares with the current usable area and updates the anchor if they differ.
     * @return true if the dimensions changed and margins need to be re-sent to AA.
     */
    fun updateSurfaceDimensions(surfaceW: Int, surfaceH: Int): Boolean {
        val finalSurfaceW: Int
        val finalSurfaceH: Int

        val screenOrientation = if (this::currentSettings.isInitialized) currentSettings.screenOrientation else Settings.ScreenOrientation.SYSTEM
        if (screenOrientation == Settings.ScreenOrientation.LANDSCAPE || 
            screenOrientation == Settings.ScreenOrientation.LANDSCAPE_REVERSE) {
            finalSurfaceW = Math.max(surfaceW, surfaceH)
            finalSurfaceH = Math.min(surfaceW, surfaceH)
        } else if (screenOrientation == Settings.ScreenOrientation.PORTRAIT || 
                   screenOrientation == Settings.ScreenOrientation.PORTRAIT_REVERSE) {
            finalSurfaceW = Math.min(surfaceW, surfaceH)
            finalSurfaceH = Math.max(surfaceW, surfaceH)
        } else {
            finalSurfaceW = surfaceW
            finalSurfaceH = surfaceH
        }

        val diffW = kotlin.math.abs(finalSurfaceW - screenWidthPx)
        val diffH = kotlin.math.abs(finalSurfaceH - screenHeightPx)

        if (diffW <= SURFACE_MISMATCH_TOLERANCE && diffH <= SURFACE_MISMATCH_TOLERANCE) {
            return false
        }

        if( (diffW > 0 && getNegotiatedWidth() == finalSurfaceW) || (diffH > 0 && getNegotiatedHeight() == finalSurfaceH)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected but matches negotiated resolution. Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH}. Ignoring.")
            return false
        }

        AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected! Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH} (diff: ${diffW}x${diffH})")

        // Update anchor: the surface dimensions ARE the real usable area,
        // so the anchor is the usable area plus insets.
        realScreenWidthPx = finalSurfaceW + systemInsetLeft + systemInsetRight
        realScreenHeightPx = finalSurfaceH + systemInsetTop + systemInsetBottom

        recalculate()

        AppLog.i("[UI_DEBUG_FIX] Recalculated: usable=${screenWidthPx}x${screenHeightPx}, margins: w=${getWidthMargin()}, h=${getHeightMargin()}, per-side: L=${getLeftMargin()} T=${getTopMargin()} R=${getRightMargin()} B=${getBottomMargin()}")
        return true
    }

    /**
     * Computes a hash of all settings that affect screen dimensions.
     * Used to invalidate the cached surface dimensions when settings change.
     */
    fun computeSettingsHash(settings: Settings): Int {
        var hash = 17
        hash = 31 * hash + settings.resolutionId
        hash = 31 * hash + settings.dpiPixelDensity
        hash = 31 * hash + settings.pixelAspectRatioE4
        hash = 31 * hash + settings.insetLeft
        hash = 31 * hash + settings.insetTop
        hash = 31 * hash + settings.insetRight
        hash = 31 * hash + settings.insetBottom
        hash = 31 * hash + settings.viewMode.ordinal
        hash = 31 * hash + settings.screenOrientation.ordinal
        hash = 31 * hash + settings.fullscreenMode.value
        hash = 31 * hash + (if (settings.stretchToFill) 1 else 0)
        hash = 31 * hash + (if (settings.forcedScale) 1 else 0)
        // Include physical dimensions in the hash. If the screen rotates or a foldable is unfolded,
        // the hash will change, triggering a clean unlock and recalculation.
        hash = 31 * hash + realScreenWidthPx
        hash = 31 * hash + realScreenHeightPx
        return hash
    }

    fun lockResolution() {
        if (!isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Locking resolution at $negotiatedResolutionType")
            isResolutionLocked = true
        }
    }

    fun unlockResolution() {
        if (isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Unlocking resolution (was $negotiatedResolutionType)")
            isResolutionLocked = false
        }
    }

    private const val SURFACE_MISMATCH_TOLERANCE = 4
}
