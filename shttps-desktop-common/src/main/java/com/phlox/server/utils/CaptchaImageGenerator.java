package com.phlox.server.utils;

import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Utility class for generating captcha images on Desktop platform using Java AWT/Graphics2D API
 */
public class CaptchaImageGenerator {
    
    public static SHTTPSPlatformUtils.ImageData generateCaptchaImage(String code, int width, int height) {
        // Create buffered image with ARGB format
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable anti-aliasing for better quality
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Fill background with light color
        g2d.setColor(new Color(240, 240, 240));
        g2d.fillRect(0, 0, width, height);
        
        // Set up font
        Font font = new Font("Arial", Font.BOLD, (int)(height * 0.6));
        g2d.setFont(font);
        
        // Draw background noise lines
        drawNoiseLines(g2d, width, height);
        
        // Draw misleading shapes
        drawMisleadingShapes(g2d, width, height);
        
        // Calculate text positioning
        FontMetrics fontMetrics = g2d.getFontMetrics();
        int textWidth = fontMetrics.stringWidth(code);
        int textHeight = fontMetrics.getHeight();
        
        // Center the text horizontally and vertically
        int startX = (width - textWidth) / 2;
        int startY = (height + textHeight) / 2 - fontMetrics.getDescent();
        
        // Draw each character with individual transformations
        int charWidth = textWidth / code.length();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            String charStr = String.valueOf(c);
            
            // Calculate character position
            int charX = startX + i * charWidth;
            int charY = startY;
            
            // Apply random transformations
            AffineTransform originalTransform = g2d.getTransform();
            g2d.translate(charX, charY);
            
            // Random displacement
            float offsetX = (float) (Math.random() * 8 - 4);
            float offsetY = (float) (Math.random() * 8 - 4);
            g2d.translate(offsetX, offsetY);
            
            // Random rotation
            double rotation = Math.random() * 0.5 - 0.25; // -0.25 to +0.25 radians
            g2d.rotate(rotation);
            
            // Random color variation
            Color baseColor = new Color(50, 50, 50);
            int r = Math.max(0, Math.min(255, baseColor.getRed() + (int)(Math.random() * 60 - 30)));
            int g = Math.max(0, Math.min(255, baseColor.getGreen() + (int)(Math.random() * 60 - 30)));
            int b = Math.max(0, Math.min(255, baseColor.getBlue() + (int)(Math.random() * 60 - 30)));
            g2d.setColor(new Color(r, g, b));
            
            // Draw character
            g2d.drawString(charStr, 0, 0);
            
            // Restore transform
            g2d.setTransform(originalTransform);
        }
        
        // Add more noise on top
        drawTopNoise(g2d, width, height);
        
        g2d.dispose();
        
        // Convert image to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "PNG", baos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode captcha image", e);
        }
        
        SHTTPSPlatformUtils.ImageData imageData = new SHTTPSPlatformUtils.ImageData();
        imageData.width = width;
        imageData.height = height;
        imageData.mimeType = "image/png";
        imageData.data = baos.toByteArray();
        
        return imageData;
    }
    
    private static void drawNoiseLines(Graphics2D g2d, int width, int height) {
        int numLines = 8 + (int)(Math.random() * 5); // 8-12 lines
        
        for (int i = 0; i < numLines; i++) {
            // Random color
            int alpha = 50 + (int)(Math.random() * 50);
            int r = (int)(Math.random() * 255);
            int g = (int)(Math.random() * 255);
            int b = (int)(Math.random() * 255);
            g2d.setColor(new Color(r, g, b, alpha));
            
            // Random line width
            float strokeWidth = 1 + (float)Math.random() * 2;
            g2d.setStroke(new BasicStroke(strokeWidth));
            
            // Draw curved line
            int startX = (int)(Math.random() * width);
            int startY = (int)(Math.random() * height);
            
            int[] xPoints = new int[4];
            int[] yPoints = new int[4];
            xPoints[0] = startX;
            yPoints[0] = startY;
            
            for (int j = 1; j < 4; j++) {
                xPoints[j] = (int)(Math.random() * width);
                yPoints[j] = (int)(Math.random() * height);
            }
            
            g2d.drawPolyline(xPoints, yPoints, 4);
        }
    }
    
    private static void drawMisleadingShapes(Graphics2D g2d, int width, int height) {
        int numShapes = 3 + (int)(Math.random() * 3); // 3-5 shapes
        
        for (int i = 0; i < numShapes; i++) {
            // Random color
            int alpha = 30 + (int)(Math.random() * 40);
            int r = (int)(Math.random() * 255);
            int g = (int)(Math.random() * 255);
            int b = (int)(Math.random() * 255);
            g2d.setColor(new Color(r, g, b, alpha));
            
            // Random position and size
            int x = (int)(Math.random() * width);
            int y = (int)(Math.random() * height);
            int size = 20 + (int)(Math.random() * 40);
            
            // Draw circle or rectangle
            if (Math.random() > 0.5) {
                g2d.fillOval(x - size/2, y - size/2, size, size);
            } else {
                g2d.fillRect(x - size/2, y - size/2, size, size);
            }
        }
    }
    
    private static void drawTopNoise(Graphics2D g2d, int width, int height) {
        int numDots = 50 + (int)(Math.random() * 30); // 50-79 dots
        
        for (int i = 0; i < numDots; i++) {
            // Random color
            int alpha = 80 + (int)(Math.random() * 50);
            int r = (int)(Math.random() * 255);
            int g = (int)(Math.random() * 255);
            int b = (int)(Math.random() * 255);
            g2d.setColor(new Color(r, g, b, alpha));
            
            // Random position and size
            int x = (int)(Math.random() * width);
            int y = (int)(Math.random() * height);
            int radius = 1 + (int)(Math.random() * 2);
            
            g2d.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }
}
