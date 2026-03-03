package dev.aetherhyt.api;

import dev.aetherhyt.ChatBubbles;
import dev.aetherhyt.manager.ChatBubblesManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public class ChatBubblesAPI {
    private ChatBubblesAPI() {
        // Utility class - prevent instantiation
    }

    /**
     * Check if the Speech Bubbles plugin is available and ready.
     *
     * @return true if the plugin is loaded and ready to use
     */
    public static boolean isAvailable() {
        return ChatBubbles.getInstance() != null
                && ChatBubbles.getInstance().getBubbleManager() != null;
    }

    /**
     * Display a speech bubble above an entity to a specific player.
     * Uses default configuration values for duration and dimensions.
     *
     * @param entityUuid The UUID of the entity to anchor the bubble to (e.g., NPC UUID)
     * @param playerUuid The UUID of the player who should see the bubble
     * @param text The text to display in the bubble
     * @return true if the bubble was successfully queued for display
     */
    public static boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text) {
        return showBubble(entityUuid, playerUuid, text, (ChatBubblesOptions) null);
    }

    /**
     * Display a speech bubble with a custom duration.
     *
     * @param entityUuid The UUID of the entity to anchor the bubble to
     * @param playerUuid The UUID of the player who should see the bubble
     * @param text The text to display in the bubble
     * @param durationMs Duration in milliseconds before the bubble disappears
     * @return true if the bubble was successfully queued for display
     */
    public static boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text, long durationMs) {
        return showBubble(entityUuid, playerUuid, text, new ChatBubblesOptions().duration(durationMs));
    }

    /**
     * Display a speech bubble with custom options.
     *
     * @param entityUuid The UUID of the entity to anchor the bubble to
     * @param playerUuid The UUID of the player who should see the bubble
     * @param text The text to display in the bubble
     * @param options Custom options for the bubble (null for defaults)
     * @return true if the bubble was successfully queued for display
     */
    public static boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text, @Nullable ChatBubblesOptions options) {
        ChatBubblesManager manager = getManager();
        if (manager == null) {
            return false;
        }
        return manager.showBubble(entityUuid, playerUuid, text, options);
    }

    /**
     * Display a speech bubble to all nearby players.
     *
     * @param entityUuid The UUID of the entity to anchor the bubble to
     * @param text The text to display in the bubble
     * @param options Custom options for the bubble (null for defaults)
     * @return the number of players who received the bubble
     */
    public static int showBubbleToAll(@Nonnull UUID entityUuid, @Nonnull String text, @Nullable ChatBubblesOptions options) {
        ChatBubblesManager manager = getManager();
        if (manager == null) {
            return 0;
        }
        return manager.showBubbleToAll(entityUuid, text, options);
    }

    /**
     * Display a speech bubble to all nearby players with default options.
     *
     * @param entityUuid The UUID of the entity to anchor the bubble to
     * @param text The text to display in the bubble
     * @return the number of players who received the bubble
     */
    public static int showBubbleToAll(@Nonnull UUID entityUuid, @Nonnull String text) {
        return showBubbleToAll(entityUuid, text, null);
    }

    /**
     * Hide all speech bubbles for a specific player.
     *
     * @param playerUuid The UUID of the player
     * @return the number of bubbles hidden
     */
    public static int hideAllBubblesForPlayer(@Nonnull UUID playerUuid) {
        ChatBubblesManager manager = getManager();
        if (manager == null) {
            return 0;
        }
        return manager.hideAllBubblesForPlayer(playerUuid);
    }

    /**
     * Hide all speech bubbles anchored to a specific entity.
     *
     * @param entityUuid The UUID of the entity
     * @return the number of bubbles hidden
     */
    public static int hideAllBubblesForEntity(@Nonnull UUID entityUuid) {
        ChatBubblesManager manager = getManager();
        if (manager == null) {
            return 0;
        }
        return manager.hideAllBubblesForEntity(entityUuid);
    }

    /**
     * Hide a specific speech bubble.
     *
     * @param bubbleId The unique ID of the bubble to hide
     * @return true if a bubble was hidden
     */
    public static boolean hideBubble(@Nonnull UUID bubbleId) {
        ChatBubblesManager manager = getManager();
        if (manager == null) {
            return false;
        }
        return manager.hideBubble(bubbleId);
    }

    /**
     * Get the number of active bubbles for a player.
     *
     * @param playerUuid The UUID of the player
     * @return the number of active bubbles
     */
    public static int getActiveBubbleCount(@Nonnull UUID playerUuid) {
        ChatBubblesManager manager = getManager();
        if (manager == null) {
            return 0;
        }
        return manager.getActiveBubbleCount(playerUuid);
    }

    /**
     * Get the speech bubble manager instance, or null if not available.
     */
    @Nullable
    private static ChatBubblesManager getManager() {
        ChatBubbles instance = ChatBubbles.getInstance();
        if (instance == null) {
            return null;
        }
        return instance.getBubbleManager();
    }
}
