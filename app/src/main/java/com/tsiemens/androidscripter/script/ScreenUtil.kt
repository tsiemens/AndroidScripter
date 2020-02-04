package com.tsiemens.androidscripter.script

import android.graphics.*
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat4
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.hypot

data class ScreenSignature(val width: Int, val height: Int, val hash: String)

@Suppress("UNUSED")
class ScreenUtil {
    companion object {
        val TAG = ScreenUtil::class.java.simpleName

        fun makePoint(x: Int, y: Int): Point {
            return Point(x, y)
        }

        @Suppress("UNUSED")
        fun scaledCoords(coord: Point, refCoord: Point, bitmap: Bitmap): Point {
            return Point(
                coord.x * bitmap.getWidth() / refCoord.x,
                coord.y * bitmap.getHeight() / refCoord.y)
        }

        /**
         * Helper to get a subset of pixels from a region of a bitmap.
         * Provide the upper left and lower right points of a rectangle on the screen.
         *
         * Returned is a Sequence for pixel coordinates that are to be sampled.
         *
         * Uses a dumb sampling algorithm right now, based on a percentage. Defaults to
         * 1 sample per 20 pixels (5% sample).
         *
         * filter, if provided, should return true if a pixel is to be used.
         */
        @Suppress("UNUSED")
        fun getPixelSampleCoords(upperLeft: Point, lowerRight: Point,
                                 pixPerSample: Int = 20,
                                 filter: ((Point)->Boolean)? = null): Sequence<Point> {
            val ul = upperLeft
            val lr = lowerRight

            val width = lr.x - ul.x
            val height = lr.y - ul.y

            val pixels = width * height
            val nSamples = (pixels/pixPerSample)

            return sequence {
                var totalPxs = 0
                var pxsUsed = 0
                var x = 0
                var y = 0
                for (i in 0 until nSamples) {
                    totalPxs++
                    val point = Point(x + ul.x, y + ul.y)
                    if (filter == null || filter(point)) {
                        pxsUsed++
                        yield(point)
                    }
                    x = (x + pixPerSample) % width
                    y += ((x + pixPerSample) / width)
                }
                Log.d(TAG, "getPixelSampleCoords: yielded $pxsUsed/$totalPxs samples")
            }
        }

        fun combineSampleCoordSequences(vararg seqs: Sequence<Point>): Sequence<Point> {
            return sequence{
                for (seq in seqs) {
                    yieldAll(seq)
                }
            }
        }

        fun pixelExcludeFilterFromWinDimen(wd: Api.WinDimen,
                                           screenWidth: Int, screenHeight: Int): (Point) -> Boolean {
            return fun(px: Point): Boolean {
                return !wd.contains(px, screenWidth, screenHeight)
            }
        }

        fun getBitmapScreenSigFromSeq(bm: Bitmap, seq: Sequence<Point>): ScreenSignature {
            val md = MessageDigest.getInstance("MD5")
            for (sample in seq) {
                val colorVal = bm.getPixel(sample.x, sample.y)
                md.update((colorVal and 0xFF).toByte())
                md.update(((colorVal.shr(8)) and 0xFF).toByte())
                md.update(((colorVal.shr(16)) and 0xFF).toByte())
                md.update(((colorVal.shr(24)) and 0xFF).toByte())
            }

            val hex = md.digest().joinToString("") { "%02x".format(it) }
            return ScreenSignature(bm.width, bm.height, hex)
        }

        // ******************************* Utils for finding X buttons *****************************

        fun toGray(bm: Bitmap): Bitmap {
            val grayMat = toGrayMat(bm)
            val outBm = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(grayMat, outBm)
            return outBm
        }

        fun toGrayWithLines(bm: Bitmap): Bitmap {
            val grayMat = toGrayMat(bm)
            val outMat = drawLines(grayMat)
            val outBm = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(outMat, outBm)
            return outBm
        }

        fun toGrayMat(bm: Bitmap): Mat {
            val mat = Mat()
            val grayMat = Mat()
            Log.d(TAG, "Bitmap config: " + bm.config.name)
            org.opencv.android.Utils.bitmapToMat(bm, mat)
            org.opencv.imgproc.Imgproc.cvtColor(mat, grayMat, org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY)
            return grayMat
        }

        fun matToGrayMat(mat: Mat): Mat {
            val grayMat = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(mat, grayMat, org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY)
            return grayMat
        }

        fun drawLines(img: Mat): Mat {
            val lsd = org.opencv.ximgproc.Ximgproc.createFastLineDetector()
            val lines = MatOfFloat4()
            lsd.detect(img, lines)

            Log.d(TAG, "lines: ${lines.elemSize()} ${lines.size()} ${lines.rows()} ${lines.cols()}")
            val elem1 = lines[0, 0]
            Log.d(TAG, "elem1: ${elem1.toString()} ${elem1.contentToString()}")

            val tmpBm = Bitmap.createBitmap(img.width(), img.height(), Bitmap.Config.RGB_565)
            tmpBm.eraseColor(Color.WHITE)
            var outMat = Mat()
            org.opencv.android.Utils.bitmapToMat(tmpBm, outMat)
            outMat = matToGrayMat(outMat)
            Log.d(TAG, "mat: ${outMat.channels()} ${outMat.empty()}")

            lsd.drawSegments(outMat, lines)
            return outMat
        }

        fun findXs(img: Bitmap): ArrayList<Cross> {
            val mat = toGrayMat(img)
            return findXsInMat(mat)
        }

        fun findXsInMat(img: Mat): ArrayList<Cross> {
            // This should be twice the size of the X we're looking for.
            val lsd = org.opencv.ximgproc.Ximgproc.createFastLineDetector()
            val linesMat = MatOfFloat4()
            lsd.detect(img, linesMat)

            val crossSideAprox = 40.0

            val lines = MatOfLines(linesMat)
            val sz = lines.size()
            var nCandiLines = 0
            val candiLines = arrayOfNulls<Line>(sz)
            for (i in 0 until sz) {
                val line = lines.get(i)
                // val str = line.lineVec.contentToString()
                // Log.d(TAG, "$i/$sz Line $str")

                // is line about the right size?
                val x = abs(line.x1() - line.x2())
                val y = abs(line.y1() - line.y2())
                if (abs(crossSideAprox - x) > 30.0 ||
                        abs(crossSideAprox - y) > 30.0) {
                    // Log.d(TAG, "  Incorrect size")
                    continue
                }

                val slope = if (y != 0.0) abs(x / y) else Double.MAX_VALUE
                if (slope > 1.5 || slope < 0.7) {
                    // Log.d(TAG, "Bad slope")
                    continue
                }

                // Log.d(TAG, "Good segment")

                candiLines[nCandiLines] = line
                nCandiLines++
            }

            Log.d(TAG, "Done finding segments. Found $nCandiLines")

            val centerDist: Double = 10.0
            val xs = arrayListOf<Cross>()
            for (i in 0 until nCandiLines) {
                val line = candiLines[i]
                val lineType = line!!.type()
                if (lineType != Line.LineType.NW_SE) {
                    continue
                }

                val cross = Cross(line)
                /* line -> \ /
                 *         / \
                 **/
                val maybeAddToCross = fun(otherLine: Line): Boolean {
                    var dist: Double
                    if (otherLine.type() == lineType) {
                        // Diagonal candidate
                        dist = pointDist(line.eastX(), line.eastY(),
                            otherLine.westX(), otherLine.westY())
                        if (dist <= centerDist) {
                            cross.se = otherLine
                            return true
                        }
                    } else {
                        // Other is /
                        dist = pointDist(line.eastX(), line.eastY(),
                            otherLine.eastX(), otherLine.eastY())
                        if (dist <= centerDist) {
                            cross.sw = otherLine
                            return true
                        }
                        dist = pointDist(line.eastX(), line.eastY(),
                            otherLine.westX(), otherLine.westY())
                        if (dist <= centerDist) {
                            cross.ne = otherLine
                            return true
                        }
                    }
                    return false
                }

                for (j in 0 until nCandiLines) {
                    if (i == j) {
                        continue
                    }

                    if (maybeAddToCross(candiLines[j]!!)) {
                        if (cross.ne != null && cross.se != null && cross.sw != null) {
                            // Filled cross
                            xs.add(cross)
                            Log.d(TAG, "Add cross: $cross")
                            break
                        }
                    }
                }
            }

            return xs
        }

        fun getLinesFromXs(xs: List<Cross>): ArrayList<Line> {
            val lines = ArrayList<Line>(xs.size * 4)
            for (cross in xs) {
                lines.add(cross.nw)
                lines.add(cross.sw!!)
                lines.add(cross.ne!!)
                lines.add(cross.se!!)
            }
            return lines
        }

        fun pointDist(x1: Double, y1: Double,
                      x2: Double, y2: Double): Double {
            return hypot(abs(x1 - x2), abs(y1 - y2))
        }

        fun drawLinesToBm(lines: ArrayList<Line>, bm: Bitmap,
                          color: Int = Color.RED, strokeWidth: Float = 3f) {
            val canvas = Canvas(bm)
            val p = Paint()
            p.color = color
            p.strokeWidth = strokeWidth
            for (line in lines) {
                canvas.drawLine(
                    line.x1().toFloat(), line.y1().toFloat(),
                    line.x2().toFloat(), line.y2().toFloat(), p)
            }
        }
    }

    class Cross(val nw: Line) {
        var ne: Line? = null
        var se: Line? = null
        var sw: Line? = null

        fun center(): org.opencv.core.Point {
            return org.opencv.core.Point(nw.eastX(), nw.eastY())
        }

        override fun toString(): String {
            return "nw: $nw\nne: ${ne.toString()}\nse: ${se.toString()}\nsw: ${sw.toString()}"
        }
    }

    class Line(val lineVec: DoubleArray) {
        // Where Vec4f is (x1, y1, x2, y2), point 1 is the start, point 2 - end.
        fun x1(): Double = lineVec[0]
        fun y1(): Double = lineVec[1]
        fun x2(): Double = lineVec[2]
        fun y2(): Double = lineVec[3]

        enum class LineType {
            NW_SE,
            SW_NE,
        }

        fun westX(): Double = if (x1() < x2()) { x1() } else { x2() }
        fun westY(): Double = if (x1() < x2()) { y1() } else { y2() }
        fun eastX(): Double = if (x1() < x2()) { x2() } else { x1() }
        fun eastY(): Double = if (x1() < x2()) { y2() } else { y1() }

        fun type(): LineType = if (westY() < eastY()) { LineType.NW_SE } else { LineType.SW_NE }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("[")
            for (i in 0 until lineVec.size) {
                sb.append(lineVec[i].toInt())
                if (i < lineVec.size - 1) {
                    sb.append(", ")
                }
            }
            sb.append("]")
            return sb.toString()
        }
    }

    class MatOfLines(val linesMat: MatOfFloat4) {
        fun size(): Int {
            return linesMat.rows()
        }

        fun get(i: Int): Line {
            val da = linesMat.get(i, 0)
            return Line(da)
        }
    }
}