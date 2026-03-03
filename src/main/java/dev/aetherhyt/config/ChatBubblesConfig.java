package dev.aetherhyt.config;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChatBubblesConfig {
    // Default values
    private static final long DEFAULT_DURATION = 5000;
    private static final int DEFAULT_MAX_WIDTH = 626;   // Original bubble image width
    private static final int DEFAULT_MAX_HEIGHT = 349;  // Original bubble image height
    private static final String DEFAULT_TEXT_COLOR = "#FFFFFF";
    private static final float DEFAULT_BACKGROUND_OPACITY = 0.9f;

    private static final int DEFAULT_CLEANUP_INTERVAL = 30;
    private static final int DEFAULT_MAX_DISTANCE = 25; // Maximum visibility distance in blocks
    private static final float DEFAULT_FOV = 75.0f;     // Default field of view in degrees
    private static final double DEFAULT_HEAD_OFFSET = -0.75; // Blocks above entity head (0 = right at head level)

    private final long defaultDuration;
    private final int defaultMaxWidth;
    private final int defaultMaxHeight;
    private final String defaultTextColor;
    private final float defaultBackgroundOpacity;

    private final int cleanupInterval;
    private final int maxDistance;
    private final float fov;
    private final double headOffset;

    private ChatBubblesConfig(Builder builder) {
        this.defaultDuration = builder.defaultDuration;
        this.defaultMaxWidth = builder.defaultMaxWidth;
        this.defaultMaxHeight = builder.defaultMaxHeight;
        this.defaultTextColor = builder.defaultTextColor;
        this.defaultBackgroundOpacity = builder.defaultBackgroundOpacity;

        this.cleanupInterval = builder.cleanupInterval;
        this.maxDistance = builder.maxDistance;
        this.fov = builder.fov;
        this.headOffset = builder.headOffset;
    }

    // ========== Getters ==========

    public long getDefaultDuration() {
        return defaultDuration;
    }

    public int getDefaultMaxWidth() {
        return defaultMaxWidth;
    }

    public int getDefaultMaxHeight() {
        return defaultMaxHeight;
    }

    public String getDefaultTextColor() {
        return defaultTextColor;
    }

    public float getDefaultBackgroundOpacity() {
        return defaultBackgroundOpacity;
    }


    public int getCleanupInterval() {
        return cleanupInterval;
    }

    public int getMaxDistance() {
        return maxDistance;
    }

    public float getFov() {
        return fov;
    }

    public double getHeadOffset() {
        return headOffset;
    }

    // ========== Loading ==========

    /**
     * Load configuration from a YAML file.
     *
     * @param configPath Path to the config file
     * @return Loaded configuration
     * @throws IOException if the file cannot be read
     */
    @Nonnull
    public static ChatBubblesConfig load(@Nonnull Path configPath) throws IOException {
        Builder builder = new Builder();

        if (!Files.exists(configPath)) {
            // Return defaults
            System.out.println("[ChatBubbles] Config file not found, using defaults: maxWidth="
                    + DEFAULT_MAX_WIDTH + ", maxHeight=" + DEFAULT_MAX_HEIGHT
                    + ", fov=" + DEFAULT_FOV);
            return builder.build();
        }

        System.out.println("[ChatBubbles] Loading config from: " + configPath);
        String content = Files.readString(configPath);
        System.out.println("[ChatBubbles] Config content:\n" + content);

        // Simple YAML parsing for our specific format
        parseYaml(content, builder);

        ChatBubblesConfig config = builder.build();
        System.out.println("[ChatBubbles] Config loaded: maxWidth=" + config.getDefaultMaxWidth()
                + ", maxHeight=" + config.getDefaultMaxHeight()
                + ", fov=" + config.getFov());

        // Warn if using old default values
        if (config.getDefaultMaxWidth() == 250 || config.getDefaultMaxHeight() == 150) {
            System.err.println("[ChatBubbles] WARNING: Config has old default values (250x150). "
                    + "Consider updating to new defaults (626x349) or delete config file to regenerate.");
        }

        return config;
    }

    /**
     * Simple YAML parser for our config format.
     */
    private static void parseYaml(String content, Builder builder) {
        String[] lines = content.split("\n");
        String currentSection = "";

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // Check for section headers
            if (trimmed.endsWith(":")) {
                currentSection = trimmed.substring(0, trimmed.length() - 1);
                continue;
            }

            // Parse key-value pairs
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex > 0) {
                String key = trimmed.substring(0, colonIndex).trim();
                String value = trimmed.substring(colonIndex + 1).trim();

                // Remove quotes if present
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                // Remove inline comments
                int commentIndex = value.indexOf(" #");
                if (commentIndex > 0) {
                    value = value.substring(0, commentIndex).trim();
                }

                parseValue(currentSection, key, value, builder);
            }
        }
    }

    private static void parseValue(String section, String key, String value, Builder builder) {
        try {
            if ("defaults".equals(section)) {
                switch (key) {
                    case "duration":
                        builder.defaultDuration = Long.parseLong(value);
                        break;
                    case "maxWidth":
                        builder.defaultMaxWidth = Integer.parseInt(value);
                        break;
                    case "maxHeight":
                        builder.defaultMaxHeight = Integer.parseInt(value);
                        break;
                    case "textColor":
                        builder.defaultTextColor = value;
                        break;
                    case "backgroundOpacity":
                        builder.defaultBackgroundOpacity = Float.parseFloat(value);
                        break;
                    case "fov":
                        builder.fov = Float.parseFloat(value);
                        break;
                    case "headOffset":
                        builder.headOffset = Double.parseDouble(value);
                        break;
                }
            } else if (section.isEmpty()) {
                switch (key) {

                    case "cleanupInterval":
                        builder.cleanupInterval = Integer.parseInt(value);
                        break;
                    case "maxDistance":
                        builder.maxDistance = Integer.parseInt(value);
                        break;
                }
            }
        } catch (NumberFormatException e) {
            // Ignore invalid values, use defaults
        }
    }

    // ========== Builder ==========

    private static class Builder {
        private long defaultDuration = DEFAULT_DURATION;
        private int defaultMaxWidth = DEFAULT_MAX_WIDTH;
        private int defaultMaxHeight = DEFAULT_MAX_HEIGHT;
        private String defaultTextColor = DEFAULT_TEXT_COLOR;
        private float defaultBackgroundOpacity = DEFAULT_BACKGROUND_OPACITY;

        private float fov = DEFAULT_FOV;
        private int cleanupInterval = DEFAULT_CLEANUP_INTERVAL;
        private int maxDistance = DEFAULT_MAX_DISTANCE;
        private double headOffset = DEFAULT_HEAD_OFFSET;

        ChatBubblesConfig build() {
            return new ChatBubblesConfig(this);
        }
    }

    public static float getDefaultFov() {
        return DEFAULT_FOV;
    }
}
