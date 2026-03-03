package dev.aetherhyt.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.matrix.Matrix4d;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import dev.aetherhyt.ChatBubbles;
import dev.aetherhyt.api.ChatBubblesOptions;
import dev.aetherhyt.config.ChatBubblesConfig;
import dev.aetherhyt.model.ChatBubble;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ChatBubblesManager {

    // Unique ID generator for bubbles
    private static final AtomicLong bubbleIdCounter = new AtomicLong(0);

    // Active bubbles: bubbleId -> SpeechBubble
    private final ConcurrentHashMap<UUID, ChatBubble> activeBubbles = new ConcurrentHashMap<>();

    // Player bubbles: playerUuid -> Set of bubbleIds
    private final ConcurrentHashMap<UUID, Set<UUID>> playerBubbles = new ConcurrentHashMap<>();

    // Entity bubbles: entityUuid -> Set of bubbleIds
    private final ConcurrentHashMap<UUID, Set<UUID>> entityBubbles = new ConcurrentHashMap<>();

    // Scheduler for cleanup tasks
    private final ScheduledExecutorService scheduler;

    private final ChatBubblesConfig config;
    private final ChatBubbles plugin;
    private volatile boolean shutdown = false;

    public ChatBubblesManager(@Nonnull ChatBubblesConfig config, @Nonnull ChatBubbles plugin) {
        this.config = config;
        this.plugin = plugin;

        // Create daemon scheduler for cleanup and position update tasks
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ChatBubbles-Worker");
            t.setDaemon(true);
            return t;
        });

        // Start periodic cleanup
        startCleanupTask();

        // Start position update task (60 FPS = ~16ms)
        startPositionUpdateTask();
    }

    /**
     * Show a speech bubble to a specific player.
     *
     * @param entityUuid The entity to anchor the bubble to
     * @param playerUuid The player who should see the bubble
     * @param text       The text to display
     * @param options    Custom options (null for defaults)
     * @return true if successful
     */
    public boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text,
                              @Nullable ChatBubblesOptions options) {
        if (shutdown) {
            return false;
        }

        // Get entity position
        Vector3d entityPos = getEntityPosition(entityUuid);
        if (entityPos == null) {
            return false;
        }
        ChatBubblesOptions defaultOptions = getDefaultOptions();

        ChatBubblesOptions effectiveOptions = (options != null ? options : new ChatBubblesOptions());

        Set<UUID> playerBubbleSet = playerBubbles.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());
        if (!playerBubbleSet.isEmpty()) {
            removeOldestBubbleForPlayer(playerUuid);
        }

        UUID bubbleId = generateBubbleId();

        ChatBubble bubble = new ChatBubble(
                bubbleId,
                entityUuid,
                playerUuid,
                text,
                effectiveOptions,
                System.currentTimeMillis(),
                entityPos.getX(), entityPos.getY(), entityPos.getZ());


        activeBubbles.put(bubbleId, bubble);
        playerBubbleSet.add(bubbleId);
        entityBubbles.computeIfAbsent(entityUuid, k -> ConcurrentHashMap.newKeySet()).add(bubbleId);
        boolean sent = sendBubbleToPlayer(bubble);

        if (sent) {
            scheduleBubbleRemoval(bubble);
        } else {
            removeBubble(bubbleId);
        }

        return sent;
    }

    public int showBubbleToAll(@Nonnull UUID entityUuid, @Nonnull String text, @Nullable ChatBubblesOptions options) {
        int count = 0;

        try {
            Collection<PlayerRef> players = Universe.get().getPlayers();
            for (PlayerRef playerRef : players) {
                if (showBubble(entityUuid, playerRef.getUuid(), text, options)) {
                    count++;
                }
            }
        } catch (Exception e) {
            // Universe might not be available
        }

        return count;
    }

    public int hideAllBubblesForPlayer(@Nonnull UUID playerUuid) {
        Set<UUID> bubbleIds = playerBubbles.get(playerUuid);
        if (bubbleIds == null || bubbleIds.isEmpty()) {
            playerBubbles.remove(playerUuid);
            return 0;
        }

        // Mark all bubbles as removing FIRST - this prevents position updates
        for (UUID bubbleId : bubbleIds) {
            ChatBubble bubble = activeBubbles.get(bubbleId);
            if (bubble != null) {
                bubble.markRemoving();
            }
        }

        // Send clear HUD command to player BEFORE removing from tracking
        // This ensures no position updates are sent while clearing
        try {
            PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
            if (playerRef != null) {
                CustomHud hudPacket = new CustomHud(
                        true, // Clear entire HUD
                        new UICommandBuilder().getCommands()
                );
                playerRef.getPacketHandler().writeNoCache(hudPacket);
            }
        } catch (Exception e) {
            // Ignore errors
        }

        // NOW remove from tracking after UI is cleared
        int count = 0;
        for (UUID bubbleId : new ArrayList<>(bubbleIds)) {
            removeBubble(bubbleId);
            count++;
        }
        playerBubbles.remove(playerUuid);
        return count;
    }

    /**
     * Hide all bubbles for a specific entity.
     *
     * @param entityUuid The entity UUID
     * @return number of bubbles hidden
     */
    public int hideAllBubblesForEntity(@Nonnull UUID entityUuid) {
        Set<UUID> bubbleIds = entityBubbles.remove(entityUuid);
        if (bubbleIds == null || bubbleIds.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (UUID bubbleId : new ArrayList<>(bubbleIds)) {
            if (hideBubble(bubbleId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Hide a specific bubble.
     *
     * @param bubbleId The bubble UUID
     * @return true if a bubble was hidden
     */
    public boolean hideBubble(@Nonnull UUID bubbleId) {
        ChatBubble bubble = activeBubbles.get(bubbleId);
        if (bubble == null) {
            return false;
        }

        // Remove from tracking FIRST - this prevents position update task from seeing it
        removeBubble(bubbleId);

        // Then clear HUD
        sendClearBubble(bubble);

        return true;
    }

    /**
     * Get the number of active bubbles for a player.
     */
    public int getActiveBubbleCount(@Nonnull UUID playerUuid) {
        Set<UUID> bubbles = playerBubbles.get(playerUuid);
        return bubbles != null ? bubbles.size() : 0;
    }

    /**
     * Shutdown the manager and clean up all resources.
     */
    public void shutdown() {
        shutdown = true;

        // Clear entire HUD for all players with active bubbles
        Set<UUID> affectedPlayers = new HashSet<>(playerBubbles.keySet());
        for (UUID playerUuid : affectedPlayers) {
            try {
                PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
                if (playerRef != null) {
                    // Send clear HUD command
                    CustomHud hudPacket = new CustomHud(
                            true, // Clear entire HUD
                            new UICommandBuilder().getCommands()
                    );
                    playerRef.getPacketHandler().writeNoCache(hudPacket);
                }
            } catch (Exception e) {
                // Ignore errors during shutdown
            }
        }

        // Clear all tracking
        activeBubbles.clear();
        playerBubbles.clear();
        entityBubbles.clear();

        // Shutdown scheduler
        scheduler.shutdownNow();
    }

    // ========== Private Helper Methods ==========

    /**
     * Generate a unique bubble ID.
     */
    @Nonnull
    private UUID generateBubbleId() {
        long counter = bubbleIdCounter.incrementAndGet();
        return new UUID(counter, System.currentTimeMillis());
    }

    /**
     * Calculate bubble dimensions based on text length.
     *
     * @param text      The text to display
     * @param maxWidth  Maximum width constraint from options
     * @param maxHeight Maximum height constraint from options
     * @return int array [bubbleWidth, bubbleHeight, textWidth, textHeight,
     *         tailTipX, tailTipY]
     */
    private int[] calculateBubbleDimensions(@Nonnull String text, int maxWidth, int maxHeight) {
        // System.out.println("[ChatBubbles] === calculateBubbleDimensions START ===");
        // System.out.println("[ChatBubbles] Input text: \"" + text + "\"");
        // System.out.println("[ChatBubbles] Input text length: " + text.length());
        // System.out.println("[ChatBubbles] Input maxWidth: " + maxWidth + ", maxHeight: " + maxHeight);

        // Constants for sizing calculations
        final int FONT_SIZE = 24;
        final float AVG_CHAR_WIDTH = FONT_SIZE * 0.55f; // Average char width ~55% of font size
        final int LINE_HEIGHT = (int) (FONT_SIZE * 1.6f); // Line height with spacing (1.6x for better multi-line spacing)
        final int HORIZONTAL_PADDING = 50; // Padding around text (25px each side)
        final int VERTICAL_PADDING = 75; // Padding around text (increased to prevent text overflow)
        final int MIN_TEXT_WIDTH = 80; // Minimum text area width
        final int MIN_TEXT_HEIGHT = 30; // Minimum text area height (one line)
        final int MAX_TEXT_WIDTH = maxWidth - HORIZONTAL_PADDING;
        final int MAX_TEXT_HEIGHT = maxHeight - VERTICAL_PADDING;

        // System.out.println("[ChatBubbles] Constants: FONT_SIZE=" + FONT_SIZE + ", AVG_CHAR_WIDTH=" + AVG_CHAR_WIDTH
        //         + ", LINE_HEIGHT=" + LINE_HEIGHT);
        // System.out.println(
        //         "[ChatBubbles] Padding: HORIZONTAL=" + HORIZONTAL_PADDING + ", VERTICAL=" + VERTICAL_PADDING);
        // System.out.println(
        //         "[ChatBubbles] Constraints: MIN_TEXT_WIDTH=" + MIN_TEXT_WIDTH + ", MAX_TEXT_WIDTH=" + MAX_TEXT_WIDTH
        //                 + ", MIN_TEXT_HEIGHT=" + MIN_TEXT_HEIGHT + ", MAX_TEXT_HEIGHT=" + MAX_TEXT_HEIGHT);

        // Calculate text area width based on content
        int textLength = text.length();
        int estimatedTextWidth = (int) (textLength * AVG_CHAR_WIDTH);
        // System.out.println("[ChatBubbles] estimatedTextWidth = " + textLength + " * " + AVG_CHAR_WIDTH + " = "
        //         + estimatedTextWidth);

        // Clamp text width to constraints
        int textWidth = Math.max(MIN_TEXT_WIDTH, Math.min(estimatedTextWidth, MAX_TEXT_WIDTH));
        // System.out.println("[ChatBubbles] textWidth after clamping: " + textWidth + " (min=" + MIN_TEXT_WIDTH
        //         + ", max=" + MAX_TEXT_WIDTH + ")");

        // Calculate number of lines needed (approximate word wrapping)
        int charsPerLine = Math.max(1, (int) (textWidth / AVG_CHAR_WIDTH));
        int numLines = Math.max(1, (int) Math.ceil((double) textLength / charsPerLine));
        // System.out.println("[ChatBubbles] charsPerLine=" + charsPerLine + ", numLines=" + numLines);

        // Calculate text height based on number of lines
        int textHeight = Math.max(MIN_TEXT_HEIGHT, Math.min(numLines * LINE_HEIGHT, MAX_TEXT_HEIGHT));
        // System.out.println("[ChatBubbles] textHeight=" + textHeight + " (raw=" + (numLines * LINE_HEIGHT)
        //         + ", min=" + MIN_TEXT_HEIGHT + ", max=" + MAX_TEXT_HEIGHT + ")");

        // Calculate bubble dimensions
        int bubbleWidth = textWidth + HORIZONTAL_PADDING;
        int bubbleHeight = textHeight + VERTICAL_PADDING;
        // System.out.println("[ChatBubbles] bubbleWidth=" + bubbleWidth + " (textWidth=" + textWidth + " + padding="
        //         + HORIZONTAL_PADDING + ")");
        // System.out.println("[ChatBubbles] bubbleHeight=" + bubbleHeight + " (textHeight=" + textHeight + " + padding="
        //         + VERTICAL_PADDING + ")");

        // Calculate tail tip position (proportional to bubble size)
        // Original image: tail tip at 80,319 on a 626x349 image
        int tailTipX = (int) (bubbleWidth * (80.0 / 626.0)); // ~12.8% of bubble width
        int tailTipY = (int) (bubbleHeight * (319.0 / 349.0)); // ~91.4% of bubble height
        // System.out.println("[ChatBubbles] tailTipX=" + tailTipX + ", tailTipY=" + tailTipY);
        // System.out.println("[ChatBubbles] === calculateBubbleDimensions END ===");

        return new int[] { bubbleWidth, bubbleHeight, textWidth, textHeight, tailTipX, tailTipY, numLines };
    }

    /**
     * Truncate text to fit within the maximum number of lines, adding "..." if
     * truncated.
     *
     * @param text         The original text
     * @param textWidth    The width of the text area
     * @param maxLines     Maximum number of lines allowed
     * @param avgCharWidth Average character width
     * @return Truncated text with "..." if needed
     */
    @Nonnull
    private String truncateText(@Nonnull String text, int textWidth, int maxLines, float avgCharWidth) {
        int charsPerLine = Math.max(1, (int) (textWidth / avgCharWidth));
        int maxChars = charsPerLine * maxLines;

        if (text.length() <= maxChars) {
            return text;
        }

        // Reserve space for "..."
        int truncateLength = maxChars - 3;
        if (truncateLength < 1) {
            truncateLength = Math.max(1, maxChars);
        }

        String truncated = text.substring(0, truncateLength) + "...";
        System.out.println(
                "[ChatBubbles] Text truncated from " + text.length() + " to " + truncated.length() + " chars");
        return truncated;
    }

    /**
     * Send the bubble UI to the player.
     * Supports multiple simultaneous bubbles with unique IDs.
     */
    private boolean sendBubbleToPlayer(@Nonnull ChatBubble bubble) {

        System.out.println("[ChatBubbles] Sending bubble " + bubble.getBubbleId() + " to player "
                + bubble.getPlayerUuid() + " for entity " + bubble.getEntityUuid());

        try {
            PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
            if (playerRef == null) {
                return false;
            }

            // Calculate dynamic dimensions based on text first
            int[] dimensions = calculateBubbleDimensions(
                    bubble.getText(),
                    bubble.getOptions().getMaxWidth(),
                    bubble.getOptions().getMaxHeight());
            int bubbleWidth = dimensions[0];
            int bubbleHeight = dimensions[1];
            int textWidth = dimensions[2];
            int textHeight = dimensions[3];
            int tailTipX = dimensions[4];
            int tailTipY = dimensions[5];
            int numLines = dimensions[6];

            // Store dimensions in bubble for position updates
            bubble.setDimensions(bubbleWidth, bubbleHeight, tailTipX, tailTipY);

            // Calculate initial screen position using the actual bubble dimensions
            Vector3d entityPos = new Vector3d(bubble.getEntityX(), bubble.getEntityY(), bubble.getEntityZ());
            int[] screenPos = project3DToScreen(bubble.getEntityUuid(), entityPos, playerRef,
                    bubbleWidth, bubbleHeight, tailTipX, tailTipY, bubble.getOptions().getFov());

            if (screenPos == null) {
                // Entity is not visible initially
                return false;
            }

            bubble.setScreenPosition(screenPos[0], screenPos[1], true);

            // Check if text needs truncation
            final int FONT_SIZE = 24;
            final int LINE_HEIGHT = (int) (FONT_SIZE * 1.6f);
            final int VERTICAL_PADDING = 75;
            int maxTextHeight = bubble.getOptions().getMaxHeight() - VERTICAL_PADDING;
            int maxLines = Math.max(1, maxTextHeight / LINE_HEIGHT);

            String displayText = bubble.getText();
            if (numLines > maxLines) {
                displayText = truncateText(bubble.getText(), textWidth, maxLines, FONT_SIZE * 0.55f);
            }

            // Build UI commands
            UICommandBuilder commandBuilder = new UICommandBuilder();

            // Always clear HUD first when showing a new bubble
            // This ensures only one bubble is visible at a time per player (prevents crashes)
            CustomHud clearPacket = new CustomHud(true, new UICommandBuilder().getCommands());
            playerRef.getPacketHandler().writeNoCache(clearPacket);

            // Append the ChatBubble.ui
            commandBuilder.append("ChatBubble.ui");

            // Set the main container anchor
            Anchor containerAnchor = new Anchor();
            containerAnchor.setLeft(Value.of(Integer.valueOf(screenPos[0])));
            containerAnchor.setTop(Value.of(Integer.valueOf(screenPos[1])));
            containerAnchor.setWidth(Value.of(Integer.valueOf(bubbleWidth)));
            containerAnchor.setHeight(Value.of(Integer.valueOf(bubbleHeight)));
            commandBuilder.setObject("#ChatContainer.Anchor", containerAnchor);

            // Set text area dimensions
            Anchor textAreaAnchor = new Anchor();
            textAreaAnchor.setLeft(Value.of(Integer.valueOf(25)));
            textAreaAnchor.setTop(Value.of(Integer.valueOf(25)));
            textAreaAnchor.setWidth(Value.of(Integer.valueOf(textWidth)));
            textAreaAnchor.setHeight(Value.of(Integer.valueOf(textHeight)));
            commandBuilder.setObject("#TextArea.Anchor", textAreaAnchor);

            // Set the text
            commandBuilder.set("#MessageText.Text", escapeForUI(displayText));

            // Set text color if specified
            if (!bubble.getOptions().getTextColor().equals(ChatBubblesOptions.DEFAULT_TEXT_COLOR)) {
                commandBuilder.set("#MessageText.Style.TextColor", bubble.getOptions().getTextColor());
            }

            CustomHud hudPacket = new CustomHud(
                    false, // Don't clear existing HUD (we already cleared above)
                    commandBuilder.getCommands());

            // Send to player
            playerRef.getPacketHandler().writeNoCache(hudPacket);

            System.out.println("[ChatBubbles] Bubble SENT " + bubble.getBubbleId() +
                    " at screen (" + screenPos[0] + ", " + screenPos[1] + ")");

            return true;

        } catch (Exception e) {
            // Log the error for debugging
            System.err.println("[ChatBubbles] Failed to send bubble to player " + bubble.getPlayerUuid() + ": "
                    + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Escape text for UI string (handle quotes and special characters).
     */
    private String escapeForUI(@Nonnull String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /**
     * Send a command to clear the bubble from the player's UI.
     */
    private void sendClearBubble(@Nonnull ChatBubble bubble) {
        try {
            PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
            if (playerRef == null) {
                return;
            }

            // Clear entire HUD (simple and reliable for single-bubble mode)
            CustomHud hudPacket = new CustomHud(
                    true, // Clear HUD
                    new UICommandBuilder().getCommands()
            );
            playerRef.getPacketHandler().writeNoCache(hudPacket);

        } catch (Exception e) {
            // Ignore errors during cleanup
        }
    }

    /**
     * Schedule the automatic removal of a bubble after its duration expires.
     */
    private void scheduleBubbleRemoval(@Nonnull ChatBubble bubble) {
        long delay = bubble.getOptions().getDuration();

        scheduler.schedule(() -> {
            hideBubble(bubble.getBubbleId());
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Remove a bubble from all tracking maps.
     */
    private void removeBubble(@Nonnull UUID bubbleId) {
        ChatBubble bubble = activeBubbles.remove(bubbleId);
        if (bubble != null) {
            Set<UUID> playerSet = playerBubbles.get(bubble.getPlayerUuid());
            if (playerSet != null) {
                playerSet.remove(bubbleId);
            }

            Set<UUID> entitySet = entityBubbles.get(bubble.getEntityUuid());
            if (entitySet != null) {
                entitySet.remove(bubbleId);
            }
        }
    }

    /**
     * Remove the oldest bubble for a player (FIFO).
     */
    private void removeOldestBubbleForPlayer(@Nonnull UUID playerUuid) {
        Set<UUID> bubbleIds = playerBubbles.get(playerUuid);
        if (bubbleIds == null || bubbleIds.isEmpty()) {
            return;
        }

        // Find oldest bubble
        ChatBubble oldest = null;
        UUID oldestId = null;

        for (UUID bubbleId : bubbleIds) {
            ChatBubble bubble = activeBubbles.get(bubbleId);
            if (bubble != null) {
                if (oldest == null || bubble.getCreatedAt() < oldest.getCreatedAt()) {
                    oldest = bubble;
                    oldestId = bubbleId;
                }
            }
        }

        if (oldestId != null) {
            hideBubble(oldestId);
        }
    }

    /**
     * Start the periodic cleanup task.
     */
    private void startCleanupTask() {
        long interval = config.getCleanupInterval();

        scheduler.scheduleAtFixedRate(() -> {
            if (shutdown) {
                return;
            }

            cleanupExpiredBubbles();
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Clean up any expired bubbles that might have been missed.
     */
    private void cleanupExpiredBubbles() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, ChatBubble> entry : activeBubbles.entrySet()) {
            ChatBubble bubble = entry.getValue();
            long expiryTime = bubble.getCreatedAt() + bubble.getOptions().getDuration();

            if (now >= expiryTime) {
                hideBubble(entry.getKey());
            }
        }
    }

    /**
     * Start the position update task that updates bubble screen positions.
     *
     * NOTE: Position updates must run on the world thread to access entity data safely.
     * We schedule on the default world, but this means bubbles only update correctly
     * when the player is in the default world. For multi-world support, this would need
     * to be enhanced to track which world each bubble is in.
     */
    private void startPositionUpdateTask() {
        // ~60 FPS for smooth updates (16ms interval)
        final long UPDATE_INTERVAL_MS = 16;
        System.out.println("[ChatBubbles] Starting position update task (every " + UPDATE_INTERVAL_MS + "ms on world thread)");

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (shutdown) {
                    return;
                }

                // Schedule the actual update on the world thread
                // We use the default world for simplicity - player and entity should be in same world
                World world = Universe.get().getDefaultWorld();
                if (world != null) {
                    world.execute(() -> {
                        try {
                            if (!shutdown) {
                                updateBubblePositions();
                            }
                        } catch (Exception e) {
                            System.err.println("[ChatBubbles] Exception in world thread update: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                System.err.println("[ChatBubbles] Exception scheduling position update: " + e.getMessage());
                e.printStackTrace();
            }
        }, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS); // ~60 updates per second
    }

    /**
     * Update all bubble positions to follow their entities.
     *
     * NOTE: This method now runs on the world thread (via world.execute()),
     * so it can safely access entity and player transform data.
     */
    private void updateBubblePositions() {
        int bubbleCount = activeBubbles.size();

        // // Debug: log when task runs
        // if (bubbleCount > 0) {
        //     System.out.println("[ChatBubbles] updateBubblePositions running with " + bubbleCount + " active bubbles");
        // }

        if (activeBubbles.isEmpty()) {
            return;
        }

        int updateCount = 0;

        for (ChatBubble bubble : activeBubbles.values()) {
            try {
                // Verify bubble is still active and not being removed
                if (!activeBubbles.containsKey(bubble.getBubbleId()) || bubble.isRemoving()) {
                    continue;
                }

                //System.out.println("[ChatBubbles] Processing bubble for entity " + bubble.getEntityUuid());

                PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
                if (playerRef == null) {
                    System.out.println("[ChatBubbles] Player not found for bubble, removing it");
                    // Player disconnected - remove the bubble to prevent crash
                    hideBubble(bubble.getBubbleId());
                    continue;
                }

                // Get FRESH entity position from world (safe to do on world thread)
                Vector3d entityPos = getEntityPosition(bubble.getEntityUuid());
                if (entityPos != null) {
                    // Update stored position
                    bubble.setEntityPosition(entityPos.getX(), entityPos.getY(), entityPos.getZ());
                    //System.out.println("[ChatBubbles] Entity pos: " + entityPos + ", bubble stored pos: ("
                    //       + bubble.getEntityX() + "," + bubble.getEntityY() + "," + bubble.getEntityZ() + ")");
                } else {
                    // Use stored position if entity not found
                    //System.out.println("[ChatBubbles] Entity position not found, using stored position: ("
                    //        + bubble.getEntityX() + "," + bubble.getEntityY() + "," + bubble.getEntityZ() + ")");
                    entityPos = new Vector3d(bubble.getEntityX(), bubble.getEntityY(), bubble.getEntityZ());
                }

                // Project to screen using FRESH player camera data (safe on world thread)
                int[] screenPos = project3DToScreen(bubble.getEntityUuid(), entityPos, playerRef,
                        bubble.getBubbleWidth(), bubble.getBubbleHeight(),
                        bubble.getTailTipX(), bubble.getTailTipY(), bubble.getOptions().getFov());

                if (screenPos == null) {
                    // Entity is too far away - hide bubble by moving off-screen
                    if (bubble.isVisible()) {
                        System.out.println("[ChatBubbles] Entity too far - marking bubble invisible");
                        bubble.setVisible(false);
                        updateBubblePosition(playerRef, bubble, -9999, -9999);
                    }
                    continue;
                } else {
                    // Ensure bubble is marked visible
                    if (!bubble.isVisible()) {
                        bubble.setVisible(true);
                    }
                }

                // Only update if position changed significantly (1 pixel threshold for smooth movement)
                int oldX = bubble.getScreenX();
                int oldY = bubble.getScreenY();
                int newX = screenPos[0];
                int newY = screenPos[1];

                int deltaX = Math.abs(newX - oldX);
                int deltaY = Math.abs(newY - oldY);

                // Debug: always log position check
                // System.out.println("[ChatBubbles] Position check: old=(" + oldX + "," + oldY + ") new=(" + newX + ","
                //         + newY + ") delta=(" + deltaX + "," + deltaY + ") visible=" + bubble.isVisible());

                // Update if position changed by at least 1 pixel or visibility changed
                if (deltaX >= 1 || deltaY >= 1 || !bubble.isVisible()) {
                    bubble.setScreenPosition(newX, newY, true);
                    updateBubblePosition(playerRef, bubble, newX, newY);
                    updateCount++;

                    // System.out.println("[ChatBubbles] Position UPDATE SENT #" + updateCount + ": (" + oldX + ","
                    //         + oldY + ") -> (" + newX + "," + newY + ")");
                }

            } catch (Exception e) {
                // Log all errors for debugging
                System.err.println("[ChatBubbles] Error in updateBubblePositions loop: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // System.out.println("[ChatBubbles] Position update cycle complete: " + updateCount + "/" + bubbleCount
        //         + " bubbles updated");
    }

    /**
     * Update a single bubble's position on screen.
     */
    private void updateBubblePosition(@Nonnull PlayerRef playerRef, @Nonnull ChatBubble bubble, int screenX,
                                      int screenY) {
        try {
            // Final check: verify bubble is still active AND not being removed before sending
            // This prevents race conditions where bubble was removed while update was queued
            ChatBubble currentBubble = activeBubbles.get(bubble.getBubbleId());
            if (currentBubble == null || currentBubble.isRemoving()) {
                return; // Bubble was removed or is being removed, don't send update
            }

            // Also verify player is still online
            PlayerRef currentPlayerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
            if (currentPlayerRef == null) {
                return; // Player disconnected, don't send update
            }

            // Use UICommandBuilder to update the anchor position dynamically
            UICommandBuilder commandBuilder = new UICommandBuilder();

            // Update the container position using stored dimensions
            Anchor anchor = new Anchor();
            anchor.setLeft(Value.of(Integer.valueOf(screenX)));
            anchor.setTop(Value.of(Integer.valueOf(screenY)));
            anchor.setWidth(Value.of(Integer.valueOf(bubble.getBubbleWidth())));
            anchor.setHeight(Value.of(Integer.valueOf(bubble.getBubbleHeight())));
            commandBuilder.setObject("#SpeechBubbleContainer.Anchor", anchor);

            // Triple-check bubble is still active right before sending
            currentBubble = activeBubbles.get(bubble.getBubbleId());
            if (currentBubble == null || currentBubble.isRemoving()) {
                return; // Bubble was removed during processing
            }

            // Send update without clearing existing UI (false = don't clear)
            CustomHud hudPacket = new CustomHud(false, commandBuilder.getCommands());
            currentPlayerRef.getPacketHandler().writeNoCache(hudPacket);

            // // Debug: log position updates occasionally
            // if (Math.random() < 0.05) { // Log ~5% of updates
            //     System.out.println("[ChatBubbles] UI position update sent: (" + screenX + "," + screenY + ")");
            // }

        } catch (Exception e) {
            // Silently ignore "element not found" errors - these happen during race conditions
            // when the bubble is being removed while an update is in flight
            String message = e.getMessage();
            if (message != null && (message.contains("not found") || message.contains("Selected element"))) {
                // Expected during cleanup, no action needed
                System.out.println("[ChatBubbles] Position update skipped - bubble being removed");
            } else {
                System.err.println("[ChatBubbles] Error updating position: " + e.getMessage());
            }
        }
    }

    /**
     * Get default options from config.
     */
    @Nonnull
    private ChatBubblesOptions getDefaultOptions() {
        return new ChatBubblesOptions()
                .duration(config.getDefaultDuration())
                .maxWidth(config.getDefaultMaxWidth())
                .maxHeight(config.getDefaultMaxHeight())
                .textColor(config.getDefaultTextColor())
                .backgroundOpacity(config.getDefaultBackgroundOpacity())
                .fov(config.getFov());
    }

    /**
     * Get entity position from the world.
     *
     * @param entityUuid The entity UUID
     * @return The entity's position, or null if not found
     */
    @Nullable
    private Vector3d getEntityPosition(@Nonnull UUID entityUuid) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                System.out.println("[ChatBubbles] getEntityPosition: Universe is null");
                return null;
            }

            java.util.Collection<World> worlds = universe.getWorlds().values();
            if (worlds.isEmpty()) {
                System.out.println("[ChatBubbles] getEntityPosition: No worlds available");
                return null;
            }

            // Try to find entity in all worlds
            for (World world : worlds) {
                EntityStore entityStore = world.getEntityStore();
                if (entityStore != null) {
                    Ref<EntityStore> entityRef = entityStore.getRefFromUUID(entityUuid);
                    if (entityRef != null && entityRef.isValid()) {
                        Store<EntityStore> store = entityRef.getStore();
                        TransformComponent transform = store.getComponent(entityRef,
                                TransformComponent.getComponentType());
                        if (transform != null) {
                            return transform.getPosition();
                        }
                    }
                }
            }
            System.out.println("[ChatBubbles] getEntityPosition: Entity " + entityUuid + " not found in any world");
        } catch (Exception e) {
            System.err.println("[ChatBubbles] getEntityPosition error: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Project 3D world position to 2D screen coordinates.
     * Takes into account player camera rotation and handles off-screen entities.
     *
     * @param entityUuid   Entity UUID for getting bounding box height
     * @param entityPos    Entity position in world
     * @param playerRef    Player reference for camera position
     * @param bubbleWidth  The bubble width in pixels
     * @param bubbleHeight The bubble height in pixels
     * @param tailTipX     The tail tip X offset
     * @param tailTipY     The tail tip Y offset
     * @param fov          Field of view in degrees
     * @return Array [screenX, screenY] or null if too far away
     */
    @Nullable
    private int[] project3DToScreen(@Nonnull UUID entityUuid, @Nonnull Vector3d entityPos, @Nonnull PlayerRef playerRef,
                                    int bubbleWidth, int bubbleHeight, int tailTipX, int tailTipY, float fov) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return null;
            }

            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }

            Vector3d playerPos = transform.getPosition();

            // Try to get camera rotation from HeadRotation component (for players)
            // Fall back to TransformComponent rotation (for NPCs without head)
            float playerYaw;
            float playerPitch;

            HeadRotation headRotation = store.getComponent(ref, HeadRotation.getComponentType());
            if (headRotation != null) {
                // Use head/camera rotation for proper view direction (pitch + yaw)
                playerYaw = headRotation.getRotation().getYaw();
                playerPitch = headRotation.getRotation().getPitch();
            } else {
                // Fall back to body transform rotation
                playerYaw = transform.getRotation().getYaw();
                playerPitch = transform.getRotation().getPitch();
            }

            // Normalize angles to [-π, π] range to prevent precision issues
            playerYaw = normalizeAngle(playerYaw);
            playerPitch = normalizeAngle(playerPitch);

            // Get entity height from bounding box for accurate head position
            double entityHeight = getEntityHeightByUuid(entityUuid);
            // Add configurable offset above head (0 = right at head level)
            double headOffset = entityHeight + config.getHeadOffset();

            System.out.println(String.format(
                    "[ChatBubbles] Entity height: %.2f, headOffset: %.2f, final Y offset: %.2f",
                    entityHeight, config.getHeadOffset(), headOffset));

            // Calculate relative position (entity - player)
            double dx = entityPos.getX() - playerPos.getX();
            double dy = entityPos.getY() - playerPos.getY() + headOffset; // Offset above entity head
            double dz = entityPos.getZ() - playerPos.getZ();

            // Distance check - don't show if too far
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            int maxDistance = config.getMaxDistance();
            if (distanceSquared > maxDistance * maxDistance) {
                System.out.println("[ChatBubbles] Entity too far: " + Math.sqrt(distanceSquared) + " blocks (max: " + maxDistance + ")");
                return null;
            }

            // Screen dimensions
            // NOTE: The Hytale server API does not expose the player's actual screen resolution.
            // We use 1920x1080 as a reference resolution. The Hytale UI system handles scaling
            // internally, so screen coordinates are transformed appropriately for each client.
            // This may result in slight misalignment for non-16:9 aspect ratios, but the bubble
            // will still be clamped to visible screen edges.
            final int SCREEN_WIDTH = 1920;
            final int SCREEN_HEIGHT = 1080;
            final int SCREEN_CENTER_X = SCREEN_WIDTH / 2;
            final int SCREEN_CENTER_Y = SCREEN_HEIGHT / 2;

            // Use Hytale's Matrix to build view matrix
            // The view matrix transforms world coordinates to camera space
            Matrix4d viewMatrix = new Matrix4d();
            viewMatrix.identity();

            // Apply camera rotations
            // Hytale uses Y-up, yaw rotates around Y, pitch rotates around X
            // Note: playerYaw and playerPitch are already in radians
            // IMPORTANT: Apply pitch FIRST, then yaw, to avoid gimbal-like rotation issues
            Matrix4d rotTemp = new Matrix4d();
            viewMatrix.rotateAxis(playerPitch, 1, 0, 0, rotTemp);  // Pitch around X (first)
            viewMatrix.rotateAxis(playerYaw, 0, 1, 0, rotTemp);    // Yaw around Y (second)

            // Transform relative position through view matrix
            // multiplyDirection applies rotation only (no translation needed since we have relative pos)
            Vector3d camSpace = new Vector3d(dx, dy, dz);
            viewMatrix.multiplyDirection(camSpace);

            // In camera space: -Z is forward (into screen), +X is right, +Y is up
            // Check if entity is behind camera (z > 0 means behind)
            boolean isBehindCamera = camSpace.getZ() > 0.01;

            // Perspective projection
            double fovRad = Math.toRadians(fov);
            double focalLength = (SCREEN_HEIGHT / 2.0) / Math.tan(fovRad / 2.0);

            // For entities behind camera, we clamp to screen edges differently
            double distance = Math.abs(camSpace.getZ());
            double scale = focalLength / Math.max(distance, 0.1);

            // Calculate raw screen coordinates
            // When looking up (negative pitch), entities appear lower on screen (Y increases)
            // When looking down (positive pitch), entities appear higher on screen (Y decreases)
            double rawX = camSpace.getX() * scale;
            double rawY = camSpace.getY() * scale;

            // Screen coordinates: center + X, center - Y (Y is inverted for screen coords)
            int rawScreenX = SCREEN_CENTER_X + (int) rawX;
            int rawScreenY = SCREEN_CENTER_Y - (int) rawY; // Y inverted: camera +Y (up) -> screen -Y (up)

            // Apply tail offset
            int screenX = rawScreenX - tailTipX;
            int screenY = rawScreenY - tailTipY;

            // Debug output
            System.out.println(String.format(
                    "[ChatBubbles] Yaw=%.2f Pitch=%.2f | dPos=(%.2f,%.2f,%.2f) | Cam=(%.2f,%.2f,%.2f) | Screen=(%d,%d)",
                    playerYaw, playerPitch, dx, dy, dz,
                    camSpace.getX(), camSpace.getY(), camSpace.getZ(),
                    screenX, screenY));

            // Clamp bubble to screen edges allowing half the bubble to be off-screen
            // This ensures the bubble is always partially visible at screen edges
            final int HALF_WIDTH = bubbleWidth / 2;
            final int HALF_HEIGHT = bubbleHeight / 2;

            // Clamp limits: allow half off-screen
            final int MIN_X = -HALF_WIDTH;
            final int MAX_X = SCREEN_WIDTH - HALF_WIDTH;
            final int MIN_Y = -HALF_HEIGHT;
            final int MAX_Y = SCREEN_HEIGHT - HALF_HEIGHT;

            int clampedX, clampedY;

            if (isBehindCamera) {
                // Entity is behind camera - clamp to closest screen edge
                // This keeps the bubble visible at the screen edge nearest to the entity

                // Clamp X: entity to the right -> right edge, to the left -> left edge
                if (camSpace.getX() > 0) {
                    clampedX = MAX_X; // Right edge
                } else {
                    clampedX = MIN_X; // Left edge
                }

                // Clamp Y: entity above -> top edge, below -> bottom edge
                if (camSpace.getY() > 0) {
                    clampedY = MIN_Y; // Top edge
                } else {
                    clampedY = MAX_Y; // Bottom edge
                }

                // System.out.println(String.format(
                //     "[ChatBubbles] Behind camera clamp: camSpace=(%.2f,%.2f) -> (%d,%d)",
                //     camSpace.getX(), camSpace.getY(), clampedX, clampedY));
            } else {
                // Entity is in front of camera - normal clamping to screen bounds (half off-screen allowed)
                clampedX = Math.max(MIN_X, Math.min(screenX, MAX_X));
                clampedY = Math.max(MIN_Y, Math.min(screenY, MAX_Y));
            }

            return new int[] { clampedX, clampedY };

        } catch (Exception e) {
            System.err.println("[ChatBubbles] Error in project3DToScreen: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get entity height from bounding box component.
     * Returns the Y dimension of the entity's bounding box.
     *
     * @param entityPos The entity position (to find the entity in world)
     * @return Entity height in blocks, or default 1.8 if not found
     */
    private double getEntityHeight(@Nonnull Vector3d entityPos) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return 1.8; // Default player height
            }

            // Try to find entity in all worlds and get its bounding box
            for (World world : universe.getWorlds().values()) {
                EntityStore entityStore = world.getEntityStore();
                if (entityStore != null) {
                    // Note: We need to find the entity at this position
                    // Since we don't have UUID here, we use a default height
                    // This is a simplified approach - ideally we'd pass the entity UUID
                    // For now, return a reasonable default based on typical entity heights
                    return 1.8; // Default height (player height)
                }
            }
        } catch (Exception e) {
            // Ignore errors, return default
        }
        return 1.8; // Default player height
    }

    /**
     * Get entity height by UUID from bounding box component.
     * This is the accurate way to get entity height.
     *
     * @param entityUuid The entity UUID
     * @return Entity height in blocks, or default 1.8 if not found
     */
    private double getEntityHeightByUuid(@Nonnull UUID entityUuid) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return 1.8;
            }

            // Search in all worlds for the entity
            for (World world : universe.getWorlds().values()) {
                EntityStore entityStore = world.getEntityStore();
                if (entityStore != null) {
                    Ref<EntityStore> entityRef = entityStore.getRefFromUUID(entityUuid);
                    if (entityRef != null && entityRef.isValid()) {
                        Store<EntityStore> store = entityRef.getStore();

                        // Try to get BoundingBox component
                        BoundingBox boundingBox = store.getComponent(entityRef,
                                BoundingBox.getComponentType());
                        if (boundingBox != null) {
                            Box box = boundingBox.getBoundingBox();
                            if (box != null) {
                                double height = box.height();
                                // Sanity check: height should be between 0.1 and 10 blocks
                                if (height > 0.1 && height < 10.0) {
                                    return height;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ChatBubbles] Error getting entity height: " + e.getMessage());
        }
        return 1.8; // Default player height
    }

    /**
     * Normalizes an angle to the range [-π, π].
     * This handles accumulated angles from continuous rotation (e.g., yaw > 2π).
     *
     * @param angle Angle in radians (can be any value)
     * @return Normalized angle in range [-π, π]
     */
    private float normalizeAngle(float angle) {
        float twoPi = (float) (2.0 * Math.PI);
        // First wrap to [0, 2π)
        angle = angle % twoPi;
        // Then adjust to [-π, π]
        if (angle > Math.PI) {
            angle -= twoPi;
        } else if (angle < -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }
}
