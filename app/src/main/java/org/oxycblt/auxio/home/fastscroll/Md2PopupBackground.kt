/*
 * Copyright (c) 2021 Auxio Project
 * Md2PopupBackground.java is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.home.fastscroll

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.resolveAttr
import kotlin.math.sqrt

/**
 * The custom drawable used as FastScrollRecyclerView's popup background.
 * This is an adaptation from AndroidFastScroll's MD2 theme.
 *
 * Attributions as per the Apache 2.0 license:
 * ORIGINAL AUTHOR: Hai Zhang [https://github.com/zhanghai]
 * PROJECT: Android Fast Scroll [https://github.com/zhanghai/AndroidFastScroll]
 * MODIFIER: OxygenCobalt [https://github.com/]
 *
 * !!! MODIFICATIONS !!!:
 * - Use modified Auxio resources instead of AFS resources
 * - Variable names are no longer prefixed with m
 * - Suppressed deprecation warning when dealing with convexness
 */
class Md2PopupBackground(context: Context) : Drawable() {
    private val paint: Paint = Paint()
    private val paddingStart: Int
    private val paddingEnd: Int
    private val path = Path()
    private val tempMatrix = Matrix()

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        updatePath()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun isAutoMirrored(): Boolean {
        return true
    }

    private fun needMirroring(): Boolean {
        return DrawableCompat.getLayoutDirection(this) == View.LAYOUT_DIRECTION_RTL
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun onBoundsChange(bounds: Rect) {
        updatePath()
    }

    private fun updatePath() {
        path.reset()
        val bounds = bounds
        var width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val r = height / 2
        val sqrt2 = sqrt(2.0).toFloat()
        // Ensure we are convex.
        width = (r + sqrt2 * r).coerceAtLeast(width)
        pathArcTo(path, r, r, r, 90f, 180f)
        val o1X = width - sqrt2 * r
        pathArcTo(path, o1X, r, r, -90f, 45f)
        val r2 = r / 5
        val o2X = width - sqrt2 * r2
        pathArcTo(path, o2X, r, r2, -45f, 90f)
        pathArcTo(path, o1X, r, r, 45f, 45f)
        path.close()
        if (needMirroring()) {
            tempMatrix.setScale(-1f, 1f, width / 2, 0f)
        } else {
            tempMatrix.reset()
        }
        tempMatrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        path.transform(tempMatrix)
    }

    override fun getPadding(padding: Rect): Boolean {
        if (needMirroring()) {
            padding[paddingEnd, 0, paddingStart] = 0
        } else {
            padding[paddingStart, 0, paddingEnd] = 0
        }
        return true
    }

    @Suppress("DEPRECATION")
    override fun getOutline(outline: Outline) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !path.isConvex) {
            // The outline path must be convex before Q, but we may run into floating point error
            // caused by calculation involving sqrt(2) or OEM implementation difference, so in this
            // case we just omit the shadow instead of crashing.
            super.getOutline(outline)
            return
        }

        outline.setConvexPath(path)
    }

    companion object {
        private fun pathArcTo(
            path: Path,
            centerX: Float,
            centerY: Float,
            radius: Float,
            startAngle: Float,
            sweepAngle: Float
        ) {
            path.arcTo(
                centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                startAngle, sweepAngle, false
            )
        }
    }

    init {
        paint.isAntiAlias = true
        paint.color = R.attr.colorControlActivated.resolveAttr(context)
        paint.style = Paint.Style.FILL
        val resources = context.resources
        paddingStart = resources.getDimensionPixelOffset(R.dimen.spacing_medium)
        paddingEnd = resources.getDimensionPixelOffset(R.dimen.popup_padding_end)
    }
}