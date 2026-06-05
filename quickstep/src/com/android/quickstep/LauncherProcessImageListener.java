/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.quickstep;

import android.graphics.ImageDecoder;
import android.graphics.ImageDecoder.ImageInfo;
import android.graphics.ImageDecoder.Source;
import android.util.ExceptionUtils;
import android.util.Size;
import androidx.annotation.VisibleForTesting;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import static java.lang.Math.max;
import static java.lang.Math.sqrt;

/**
 * The default image decoder listener for the Launcher process.
 *
 * This listener is used to downscale images that are larger than a certain memory limit.
 */
public class LauncherProcessImageListener implements ImageDecoder.OnHeaderDecodedListener {
    // Assume worst-case (RGBA_F16) to safely fit in memory.
    private static final int BYTES_PER_PIXEL = 8;

    /**
     * The maximum size in bytes of an image that will be decoded. If the image is
     * larger than this, it will be downscaled, retaining the aspect ratio, until its size
     * is no larger than this value.
     */
    private final long maxMemoryBytes;

    /**
     * The set of mime types that are allowed to be decoded. If the image's mime type is
     * not in this set, a RuntimeException will be thrown.
     */
    private final Set<String> allowedMimeTypes;

    public LauncherProcessImageListener(long maxMemoryBytes, Set<String> allowedMimeTypes) {
        this.maxMemoryBytes = maxMemoryBytes;
        this.allowedMimeTypes = allowedMimeTypes;
    }

    @Override
    public void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source) {
        try {
            onHeaderDecoded(new AndroidImageDecoderWrapper(decoder, info));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    void onHeaderDecoded(ImageDecoderWrapper decoder) throws IOException {
        // First check that the image is a supported type based on our allowlist.
        String mimeType = decoder.getMimeType();
        if (mimeType == null || !allowedMimeTypes.contains(mimeType.toLowerCase(Locale.US))) {
            throw new IOException("Image mime type (" + mimeType + ") is not allowed.");
        }

        // Remote size may have returned a giant image, so we need to defensively limit it
        // to something reasonable.
        Size size = getPreferredSize(
                decoder.getSize().getWidth(),
                decoder.getSize().getHeight(),
                maxMemoryBytes);
        if (size != null) {
            decoder.setTargetSize(size.getWidth(), size.getHeight());
        }
    }

    /** Abstraction for {@link ImageDecoder} to allow testing. */
    public interface ImageDecoderWrapper {
        /** The size of the image in pixels. */
        Size getSize();

        /** The mime type of the image. */
        String getMimeType();

        /** Sets the target size for the image. */
        void setTargetSize(int width, int height);
    }

    /**
     * A wrapper around {@link ImageDecoder} that implements {@link ImageDecoderWrapper}.
     */
    private static class AndroidImageDecoderWrapper implements ImageDecoderWrapper {
        private final ImageDecoder decoder;
        private final ImageInfo info;

        AndroidImageDecoderWrapper(ImageDecoder decoder, ImageInfo info) {
            this.decoder = decoder;
            this.info = info;
        }

        @Override
        public Size getSize() {
            return info.getSize();
        }

        @Override
        public String getMimeType() {
            return info.getMimeType();
        }

        @Override
        public void setTargetSize(int width, int height) {
            decoder.setTargetSize(width, height);
        }
    }

    /**
     * Returns the preferred size to use for the given image size and max memory.
     *
     * If the image is larger than the max memory, this will return a size that will result in
     * the image size being no larger than the max memory. If the image is smaller than the max
     * memory, this will return null.
     *
     * @param width The width of the image in pixels.
     * @param height The height of the image in pixels.
     * @param maxMemoryBytes The maximum size in bytes of the image.
     * @return The preferred size to use for the image, or null if no downscaling is needed.
     */
    static Size getPreferredSize(int width, int height, long maxMemoryBytes) {
        // estimated size in memory as bytes
        long estimatedSize = (long) width * (long) height * BYTES_PER_PIXEL;

        // If the image is larger than the max memory, we need to downscale it.
        if (estimatedSize > maxMemoryBytes) {
            // Ratio of the estimated size to the max memory.
            long maxPixels = maxMemoryBytes / BYTES_PER_PIXEL;
            // Derive the scale factor to apply to the image to get the desired size.
            double scale = sqrt((double) maxPixels / ((long) width * (long) height));
            // Ensure at least 1 pixel dimensions
            // follow image decoder rounding behavior
            int targetWidth = max(1, (int) (width * scale + 0.5));
            int targetHeight = max(1, (int) (height * scale + 0.5));
            return new Size(targetWidth, targetHeight);
        }
        return null;
    }
}
