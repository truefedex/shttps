package com.phlox.simpleserver.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;

/**
 * Utility class for generating captcha images on Android platform using Bitmap/Canvas API
 */
public class CaptchaImageGenerator {
    
    public static SHTTPSPlatformUtils.ImageData generateCaptchaImage(String code, int width, int height) {
        // Create bitmap with ARGB_8888 format for better quality
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // Fill background with light color
        canvas.drawColor(Color.rgb(240, 240, 240));
        
        // Create paint objects
        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(height * 0.6f);
        textPaint.setStyle(Paint.Style.FILL);
        
        Paint noisePaint = new Paint();
        noisePaint.setAntiAlias(true);
        noisePaint.setStyle(Paint.Style.STROKE);
        noisePaint.setStrokeWidth(2f);
        
        // Draw background noise lines
        drawNoiseLines(canvas, width, height, noisePaint);
        
        // Draw misleading shapes
        drawMisleadingShapes(canvas, width, height, noisePaint);
        
        // Calculate text positioning
        Rect textBounds = new Rect();
        textPaint.getTextBounds(code, 0, code.length(), textBounds);
        float textWidth = textBounds.width();
        float textHeight = textBounds.height();
        
        // Center the text horizontally and vertically
        float startX = (width - textWidth) / 2f;
        float startY = (height + textHeight) / 2f;
        
        // Draw each character with individual transformations
        float charWidth = textWidth / code.length();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            String charStr = String.valueOf(c);
            
            // Calculate character position
            float charX = startX + i * charWidth;
            float charY = startY;
            
            // Apply random transformations
            canvas.save();
            
            // Random displacement
            float offsetX = (float) (Math.random() * 8 - 4);
            float offsetY = (float) (Math.random() * 8 - 4);
            canvas.translate(charX + offsetX, charY + offsetY);
            
            // Random rotation
            float rotation = (float) (Math.random() * 30 - 15); // -15 to +15 degrees
            canvas.rotate(rotation);
            
            // Random color variation
            int baseColor = Color.rgb(50, 50, 50);
            int r = Math.max(0, Math.min(255, Color.red(baseColor) + (int)(Math.random() * 60 - 30)));
            int g = Math.max(0, Math.min(255, Color.green(baseColor) + (int)(Math.random() * 60 - 30)));
            int b = Math.max(0, Math.min(255, Color.blue(baseColor) + (int)(Math.random() * 60 - 30)));
            textPaint.setColor(Color.rgb(r, g, b));
            
            // Draw character
            canvas.drawText(charStr, 0, 0, textPaint);
            
            canvas.restore();
        }
        
        // Add more noise on top
        drawTopNoise(canvas, width, height, noisePaint);
        
        // Convert bitmap to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        
        SHTTPSPlatformUtils.ImageData imageData = new SHTTPSPlatformUtils.ImageData();
        imageData.width = width;
        imageData.height = height;
        imageData.mimeType = "image/png";
        imageData.data = baos.toByteArray();
        
        return imageData;
    }
    
    private static void drawNoiseLines(Canvas canvas, int width, int height, Paint paint) {
        int numLines = 8 + (int)(Math.random() * 5); // 8-12 lines
        
        for (int i = 0; i < numLines; i++) {
            // Random color
            int color = Color.argb(50 + (int)(Math.random() * 50), 
                                  (int)(Math.random() * 255), 
                                  (int)(Math.random() * 255), 
                                  (int)(Math.random() * 255));
            paint.setColor(color);
            
            // Random line width
            paint.setStrokeWidth(1 + (float)Math.random() * 2);
            
            // Draw curved line
            Path path = new Path();
            float startX = (float)(Math.random() * width);
            float startY = (float)(Math.random() * height);
            path.moveTo(startX, startY);
            
            // Add curve points
            for (int j = 0; j < 3; j++) {
                float x = (float)(Math.random() * width);
                float y = (float)(Math.random() * height);
                path.quadTo((startX + x) / 2, (startY + y) / 2, x, y);
                startX = x;
                startY = y;
            }
            
            canvas.drawPath(path, paint);
        }
    }
    
    private static void drawMisleadingShapes(Canvas canvas, int width, int height, Paint paint) {
        int numShapes = 3 + (int)(Math.random() * 3); // 3-5 shapes
        
        for (int i = 0; i < numShapes; i++) {
            // Random color
            int color = Color.argb(30 + (int)(Math.random() * 40), 
                                  (int)(Math.random() * 255), 
                                  (int)(Math.random() * 255), 
                                  (int)(Math.random() * 255));
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            
            // Random position and size
            float x = (float)(Math.random() * width);
            float y = (float)(Math.random() * height);
            float radius = 10 + (float)(Math.random() * 20);
            
            // Draw circle or rectangle
            if (Math.random() > 0.5) {
                canvas.drawCircle(x, y, radius, paint);
            } else {
                canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint);
            }
        }
    }
    
    private static void drawTopNoise(Canvas canvas, int width, int height, Paint paint) {
        int numDots = 50 + (int)(Math.random() * 30); // 50-79 dots
        
        for (int i = 0; i < numDots; i++) {
            // Random color
            int color = Color.argb(80 + (int)(Math.random() * 50), 
                                  (int)(Math.random() * 255), 
                                  (int)(Math.random() * 255), 
                                  (int)(Math.random() * 255));
            paint.setColor(color);
            
            // Random position
            float x = (float)(Math.random() * width);
            float y = (float)(Math.random() * height);
            float radius = 1 + (float)(Math.random() * 2);
            
            canvas.drawCircle(x, y, radius, paint);
        }
    }
}

