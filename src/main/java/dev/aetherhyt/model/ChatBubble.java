package dev.aetherhyt.model;

import dev.aetherhyt.api.ChatBubblesOptions;

import javax.annotation.Nonnull;
import java.util.UUID;

public class ChatBubble {
    private final UUID bubbleId;
    private final UUID entityUuid;
    private final UUID playerUuid;
    private final String text;
    private final ChatBubblesOptions options;
    private final long createdAt;

    // Entity position in world (updated each tick)
    private double entityX;
    private double entityY;
    private double entityZ;

    // Screen position (calculated from world position)
    private int screenX = -1;
    private int screenY = -1;
    private boolean visible = false;
    private volatile boolean removing = false; // Flag to prevent race conditions during removal



    // Bubble dimensions (calculated from text) - defaults to original image size
    private int bubbleWidth = 626;
    private int bubbleHeight = 349;
    private int tailTipX = 80;   // Original: 80/626
    private int tailTipY = 319;  // Original: 319/349

    public ChatBubble(
            @Nonnull UUID bubbleId,
            @Nonnull UUID entityUuid,
            @Nonnull UUID playerUuid,
            @Nonnull String text,
            @Nonnull ChatBubblesOptions options,
            long createdAt) {
        this.bubbleId = bubbleId;
        this.entityUuid = entityUuid;
        this.playerUuid = playerUuid;
        this.text = text;
        this.options = options;
        this.createdAt = createdAt;
        this.entityX = 0;
        this.entityY = 0;
        this.entityZ = 0;
    }

    public ChatBubble(
            @Nonnull UUID bubbleId,
            @Nonnull UUID entityUuid,
            @Nonnull UUID playerUuid,
            @Nonnull String text,
            @Nonnull ChatBubblesOptions options,
            long createdAt,
            double entityX, double entityY, double entityZ) {
        this.bubbleId = bubbleId;
        this.entityUuid = entityUuid;
        this.playerUuid = playerUuid;
        this.text = text;
        this.options = options;
        this.createdAt = createdAt;
        this.entityX = entityX;
        this.entityY = entityY;
        this.entityZ = entityZ;
    }

    // ========== Getters ==========

    @Nonnull
    public UUID getBubbleId() {
        return bubbleId;
    }

    @Nonnull
    public UUID getEntityUuid() {
        return entityUuid;
    }

    @Nonnull
    public UUID getPlayerUuid() {
        return playerUuid;
    }

    @Nonnull
    public String getText() {
        return text;
    }

    @Nonnull
    public ChatBubblesOptions getOptions() {
        return options;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    // ========== Utility Methods ==========

    /**
     * Check if this bubble has expired based on its duration.
     *
     * @param currentTime Current time in milliseconds
     * @return true if the bubble has expired
     */
    public boolean isExpired(long currentTime) {
        return currentTime >= createdAt + options.getDuration();
    }

    /**
     * Get the remaining time before this bubble expires.
     *
     * @param currentTime Current time in milliseconds
     * @return remaining time in milliseconds (0 if expired)
     */
    public long getRemainingTime(long currentTime) {
        long expiryTime = createdAt + options.getDuration();
        return Math.max(0, expiryTime - currentTime);
    }

    @Override
    public String toString() {
        return "SpeechBubble{" +
                "bubbleId=" + bubbleId +
                ", entityUuid=" + entityUuid +
                ", playerUuid=" + playerUuid +
                ", text='" + text + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatBubble that = (ChatBubble) o;
        return bubbleId.equals(that.bubbleId);
    }

    @Override
    public int hashCode() {
        return bubbleId.hashCode();
    }

    // ========== Position Getters/Setters ==========

    public double getEntityX() { return entityX; }
    public double getEntityY() { return entityY; }
    public double getEntityZ() { return entityZ; }

    public void setEntityPosition(double x, double y, double z) {
        this.entityX = x;
        this.entityY = y;
        this.entityZ = z;
    }

    public int getScreenX() { return screenX; }
    public int getScreenY() { return screenY; }
    public boolean isVisible() { return visible; }

    public void setScreenPosition(int x, int y, boolean visible) {
        this.screenX = x;
        this.screenY = y;
        this.visible = visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Check if this bubble is being removed (to prevent race conditions).
     *
     * @return true if the bubble is in the process of being removed
     */
    public boolean isRemoving() {
        return removing;
    }

    /**
     * Mark this bubble as being removed.
     * This prevents position updates from being sent while the bubble is being cleaned up.
     */
    public void markRemoving() {
        this.removing = true;
    }

    // ========== Dimension Getters/Setters ==========

    public int getBubbleWidth() { return bubbleWidth; }
    public int getBubbleHeight() { return bubbleHeight; }
    public int getTailTipX() { return tailTipX; }
    public int getTailTipY() { return tailTipY; }

    public void setDimensions(int bubbleWidth, int bubbleHeight, int tailTipX, int tailTipY) {
        this.bubbleWidth = bubbleWidth;
        this.bubbleHeight = bubbleHeight;
        this.tailTipX = tailTipX;
        this.tailTipY = tailTipY;
    }
}
