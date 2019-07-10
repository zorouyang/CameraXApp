/*
 * Copyright (C) 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zorouyang.cameraxapp.util;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.util.Size;
import com.google.zxing.*;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final public class Decoder {

    private static final String TAG = Decoder.class.getSimpleName();

//    private static final int MIN_FRAME_WIDTH = 240;
//    private static final int MIN_FRAME_HEIGHT = 240;
//    private static final int MAX_FRAME_WIDTH = 675; // = 5/8 * 1080
//    private static final int MAX_FRAME_HEIGHT = 1200; // = 5/8 * 1920

    private final OnResultListener onResultListener;
    //private final MultiFormatReader multiFormatReader;
    private final QRCodeReader mQrCodeReader;
    private final Map<DecodeHintType, Object> mHints;

    private final Point screenResolution = new Point();

    private Rect framingRect;
    private Rect framingRectInPreview;

    public interface OnResultListener {
        void onResult(Result result);
    }

    public Decoder(Size resolution, OnResultListener onResultListener) {
        this.onResultListener = onResultListener;

        // Get screen resolution
        this.screenResolution.x = resolution.getWidth();
        this.screenResolution.y = resolution.getHeight();

        // Get decode hints
        /*Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, ALL_FORMATS);

        multiFormatReader = new MultiFormatReader();
        multiFormatReader.setHints(hints);*/

        mHints = new EnumMap<>(DecodeHintType.class);
        mHints.put(DecodeHintType.CHARACTER_SET, "utf-8");
        mHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        mHints.put(DecodeHintType.POSSIBLE_FORMATS, BarcodeFormat.QR_CODE);

        mQrCodeReader = new QRCodeReader();
    }

    /**
     * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
     * reuse the same reader objects from one decode to the next.
     *
     * @param data   The YUV preview frame.
     * @param width  The width of the preview frame.
     * @param height The height of the preview frame.
     */
    public void decode(byte[] data, int width, int height) {
        long start = System.nanoTime();
        Result rawResult = null;
        PlanarYUVLuminanceSource source;

        source = buildLuminanceSource(data, width, height);
        rawResult = decodeBySource(source);

        if (rawResult == null) {
            // 直接返回整幅图像的数据，而不计算聚焦框大小
            source = new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
            //Log.d(TAG, "Scan all the preview areas again without clipping, try again.");
            rawResult = decodeBySource(source);
        }

        if (rawResult != null) {
            // Don't log the barcode contents for security.
            long end = System.nanoTime();
            Log.d(TAG, "Found barcode in " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
            onResultListener.onResult(rawResult);
        }
    }

    public Result decode(String path) {
        long start = System.nanoTime();
        Result rawResult = null;

        Bitmap bitmap = ImageDecoder.decodeSampledBitmap(path);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Log.d(TAG, "decodeQRCode bitmap: " + width + "x" + height);

        /*int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);*/

        LuminanceSource source = new BitmapLuminanceSource(bitmap);
        rawResult = decodeBySource(source);

        if (rawResult == null) {
            Log.d(TAG, "rawResult=null, convert to YUV420sp, PlanarYUVLuminanceSource try again.");

            bitmap = ImageDecoder.cropImage(bitmap);
            width = bitmap.getWidth();
            height = bitmap.getHeight();
            Log.d(TAG, "decodeQRCode cropImage: " + width + "x" + height);

            byte[] data = ImageDecoder.getYUV420sp(width, height, bitmap);
            source = new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);

            rawResult = decodeBySource(source);
        }

        if (rawResult != null) {
            // Don't log the barcode contents for security.
            long end = System.nanoTime();
            Log.d(TAG, "Found barcode in " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
            onResultListener.onResult(rawResult);
        }
        return rawResult;
    }

    private Result decodeBySource(LuminanceSource source) {
        Result rawResult = null;
        if (source != null) {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            try {
                rawResult = mQrCodeReader.decode(bitmap, mHints);
            } catch (ReaderException ignored) {
            } finally {
                mQrCodeReader.reset();
            }
            //If can't scan, invert again.
            if (rawResult == null) {
                LuminanceSource invertedSource = source.invert();
                bitmap = new BinaryBitmap(new HybridBinarizer(invertedSource));
                try {
                    rawResult = mQrCodeReader.decode(bitmap, mHints);
                } catch (ReaderException e) {
                } finally {
                    mQrCodeReader.reset();
                }
            }
        }
        return rawResult;
    }

    /**
     * Calculates the framing rect which the UI should draw to show the user where to place the
     * barcode. This target helps with alignment as well as forces the user to hold the device
     * far enough away to ensure the image will be in focus.
     *
     * @return The rectangle to draw on screen in window coordinates.
     */
    public synchronized Rect getFramingRect() {
        if (framingRect == null) {
            int width, height;
//            width = findDesiredDimensionInRange(screenResolution.x, MIN_FRAME_WIDTH, MAX_FRAME_WIDTH);
//            height = findDesiredDimensionInRange(screenResolution.y, MIN_FRAME_HEIGHT, MAX_FRAME_HEIGHT);
            width = screenResolution.x * 5 / 8;
            height = width;

            int leftOffset = (screenResolution.x - width) / 2;
            int topOffset = (screenResolution.y - height) / 2;
            framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
            Log.d(TAG, "Calculated framing rect: " + framingRect.toString() + ", " + width + "x" + height);
        }

        return framingRect;
    }

    /*private static int findDesiredDimensionInRange(int resolution, int hardMin, int hardMax) {
        int dim = 5 * resolution / 8; // Target 5/8 of each dimension
        if (dim < hardMin) {
            return hardMin;
        }
        if (dim > hardMax) {
            return hardMax;
        }
        return dim;
    }*/

    /**
     * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
     * not UI / screen.
     *
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return {@link Rect} expressing barcode scan area in terms of the preview size
     */
    private synchronized Rect getFramingRectInPreview(int width, int height) {
        if (framingRectInPreview == null) {
            Rect framingRect = getFramingRect();
            if (framingRect == null) {
                return null;
            }
            Rect rect = new Rect(framingRect);

            Log.d(TAG, "cameraResolution: " + width + "x" + height + ", screenResolution: " + screenResolution.x + "x" + screenResolution.y);

            Point cameraResolution = new Point(width, height);
            rect.left = rect.left * cameraResolution.x / screenResolution.x;
            rect.right = rect.right * cameraResolution.x / screenResolution.x;
            rect.top = rect.top * cameraResolution.y / screenResolution.y;
            rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
            framingRectInPreview = rect;

            Log.d(TAG, "Calculated framingRectInPreview: " + framingRectInPreview.toString() + ", " + framingRectInPreview.width() + "x" + framingRectInPreview.height());
        }
        return framingRectInPreview;
    }

    /**
     * A factory method to build the appropriate LuminanceSource object based on the format
     * of the preview buffers, as described by Camera.Parameters.
     *
     * @param data   A preview frame.
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return A PlanarYUVLuminanceSource instance.
     */
    private PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = getFramingRectInPreview(width, height);
        if (rect == null) {
            return null;
        }

        return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                rect.width(), rect.height(), false);
    }

    /*private static Collection<BarcodeFormat> getDecodeHintType(Context context) {
//        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Collection<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

//        if (sharedPreferences.getBoolean("AZTEC", false)) {
            decodeFormats.add(BarcodeFormat.AZTEC);
//        }
//        if (sharedPreferences.getBoolean("CODABAR", false)) {
            decodeFormats.add(BarcodeFormat.CODABAR);
//        }
//        if (sharedPreferences.getBoolean("CODE_39", false)) {
            decodeFormats.add(BarcodeFormat.CODE_39);
//        }
//        if (sharedPreferences.getBoolean("CODE_93", false)) {
            decodeFormats.add(BarcodeFormat.CODE_93);
//        }
//        if (sharedPreferences.getBoolean("CODE_128", false)) {
            decodeFormats.add(BarcodeFormat.CODE_128);
//        }
//        if (sharedPreferences.getBoolean("DATA_MATRIX", false)) {
            decodeFormats.add(BarcodeFormat.DATA_MATRIX);
//        }
//        if (sharedPreferences.getBoolean("EAN_8", false)) {
            decodeFormats.add(BarcodeFormat.EAN_8);
//        }
//        if (sharedPreferences.getBoolean("EAN_13", false)) {
            decodeFormats.add(BarcodeFormat.EAN_13);
//        }
//        if (sharedPreferences.getBoolean("ITF", false)) {
            decodeFormats.add(BarcodeFormat.ITF);
//        }
//        if (sharedPreferences.getBoolean("MAXICODE", false)) {
            decodeFormats.add(BarcodeFormat.MAXICODE);
//        }
//        if (sharedPreferences.getBoolean("PDF_417", false)) {
            decodeFormats.add(BarcodeFormat.PDF_417);
//        }
//        if (sharedPreferences.getBoolean("QR_CODE", false)) {
            decodeFormats.add(BarcodeFormat.QR_CODE);
//        }
//        if (sharedPreferences.getBoolean("RSS_14", false)) {
            decodeFormats.add(BarcodeFormat.RSS_14);
//        }
//        if (sharedPreferences.getBoolean("RSS_EXPANDED", false)) {
            decodeFormats.add(BarcodeFormat.RSS_EXPANDED);
//        }
//        if (sharedPreferences.getBoolean("UPC_A", false)) {
            decodeFormats.add(BarcodeFormat.UPC_A);
//        }
//        if (sharedPreferences.getBoolean("UPC_E", false)) {
            decodeFormats.add(BarcodeFormat.UPC_E);
//        }

        return decodeFormats;
    }*/

    public static final List<BarcodeFormat> ALL_FORMATS = new ArrayList<>();
    static {
        ALL_FORMATS.add(BarcodeFormat.AZTEC);
        ALL_FORMATS.add(BarcodeFormat.CODABAR);
        ALL_FORMATS.add(BarcodeFormat.CODE_39);
        ALL_FORMATS.add(BarcodeFormat.CODE_93);
        ALL_FORMATS.add(BarcodeFormat.CODE_128);
        ALL_FORMATS.add(BarcodeFormat.DATA_MATRIX);
        ALL_FORMATS.add(BarcodeFormat.EAN_8);
        ALL_FORMATS.add(BarcodeFormat.EAN_13);
        ALL_FORMATS.add(BarcodeFormat.ITF);
        ALL_FORMATS.add(BarcodeFormat.MAXICODE);
        ALL_FORMATS.add(BarcodeFormat.PDF_417);
        ALL_FORMATS.add(BarcodeFormat.QR_CODE);
        ALL_FORMATS.add(BarcodeFormat.RSS_14);
        ALL_FORMATS.add(BarcodeFormat.RSS_EXPANDED);
        ALL_FORMATS.add(BarcodeFormat.UPC_A);
        ALL_FORMATS.add(BarcodeFormat.UPC_E);
        ALL_FORMATS.add(BarcodeFormat.UPC_EAN_EXTENSION);
    }
}