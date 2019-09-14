package com.tsiemens.androidscripter.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import java.lang.Integer.max
import java.lang.Integer.min

class BitmapUtil {
    companion object {
        fun rotationAngle(ro: Int): Float {
            return when(ro) {
                Surface.ROTATION_0 -> 0f
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> throw IllegalArgumentException("Invalid rotation: $ro")
            }
        }

        /** @angle - in degrees */
        fun rotate(bitmap: Bitmap, angle: Float): Bitmap {
            val matrix = Matrix()
            matrix.postRotate(angle)
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        /** Takes @bitmap, and crops it to the size it should be,
         * based on the screen orientation at the time.
         *
         * */
        fun cropRotatedScreenshot(bitmap: Bitmap): Bitmap {
            if (bitmap.height > bitmap.width) {
                val widthToHeightRatio: Float = bitmap.width / bitmap.height.toFloat()

                val screenWidth = bitmap.width
                val screenHeight = (screenWidth * widthToHeightRatio).toInt()
                val yWhitespaceQuanity = bitmap.height - screenHeight

                return Bitmap.createBitmap(bitmap, 0, yWhitespaceQuanity / 2, screenWidth, screenHeight)
            } else {
                // TODO don't know how to handle this yet
            }
            return bitmap
        }

        fun cropScreenshotFromSquareScreenshot(bitmap: Bitmap,
                                               screenSide1: Int, screenSide2: Int, rotation: Int): Bitmap {
            // Should be square. Just take width
            val bitmapSideLen = bitmap.width
            val longSide = max(screenSide1, screenSide2)
            val shortSide = min(screenSide1, screenSide2)

            val whitespace = bitmapSideLen - shortSide

            return when(rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 ->
                    Bitmap.createBitmap(bitmap, whitespace / 2, 0, shortSide, longSide)
                Surface.ROTATION_90, Surface.ROTATION_270 ->
                    Bitmap.createBitmap(bitmap, 0, whitespace / 2, longSide, shortSide)
                else -> throw IllegalArgumentException("Invalid rotation: $rotation")
            }

        }
    }
}