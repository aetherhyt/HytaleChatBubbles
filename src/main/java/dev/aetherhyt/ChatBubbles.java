package dev.aetherhyt;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.aetherhyt.config.ChatBubblesConfig;
import dev.aetherhyt.listener.ChatListener;
import dev.aetherhyt.manager.ChatBubblesManager;
import javax.annotation.Nonnull;
import java.io.IOException;import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;import java.nio.file.StandardCopyOption;

public class ChatBubbles extends  JavaPlugin
{
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final String VERSION = "1.0.0";

    private static ChatBubbles instance;

    private ChatBubblesManager bubbleManager;
    private ChatBubblesConfig config;
    private Path dataFolder;

    public ChatBubbles(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("Chat Bubbles mod called - version " + VERSION);
    }

    public static ChatBubbles getInstance() {
        return instance;
    }

    public ChatBubblesManager getBubbleManager() {
        return bubbleManager;
    }

    public ChatBubblesConfig getConfig() {
        return config;
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Chat Bubbles mod starting...");

        try {
            // Get data folder
            dataFolder = getDataDirectory();
            LOGGER.atInfo().log("Data directory: " + dataFolder.toString());

            Files.createDirectories(dataFolder);

            // Copy default config if needed
            copyDefaultConfig();

            // Load configuration
            config = ChatBubblesConfig.load(dataFolder.resolve("config.yml"));
            LOGGER.atInfo().log("Configuration loaded");

            // Register for shutdown event
            getEventRegistry().register(ShutdownEvent.class, this::onServerShutdown);

            LOGGER.atInfo().log("ChatBubbles setup() complete");

        } catch (Exception e) {
            LOGGER.atSevere().log("Failed during setup phase: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Start phase - runs AFTER asset loading
     */
    @Override
    protected void start() {
        try {
            // Ensure data folder exists
            if (dataFolder == null) {
                dataFolder = getDataDirectory();
                Files.createDirectories(dataFolder);
            }

            // Reload config if needed
            if (config == null) {
                copyDefaultConfig();
                config = ChatBubblesConfig.load(dataFolder.resolve("config.yml"));
            }

            // Initialize the Chat bubble manager
            bubbleManager = new ChatBubblesManager(config, this);

            // Register Chat Listener
            ChatListener chatListener = new ChatListener(this, bubbleManager);
            getEventRegistry().registerGlobal(PlayerChatEvent.class, chatListener::onPlayerChat);

        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to enable SpeechBubbles: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shutdown phase - cleanup resources
     */
    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("SpeechBubbles shutdown() starting...");

        if (bubbleManager != null) {
            bubbleManager.shutdown();
        }

        instance = null;
        LOGGER.atInfo().log("SpeechBubbles shutdown() complete");
    }

    /**
     * Handle server shutdown event
     */
    private void onServerShutdown(ShutdownEvent event) {
        LOGGER.atInfo().log("SpeechBubbles received shutdown event");
        if (bubbleManager != null) {
            bubbleManager.shutdown();
        }
    }

    /**
     * Copy default config.yml from resources if it doesn't exist
     */
    private void copyDefaultConfig() {
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.atInfo().log("Default config.yml created at " + configPath);
                } else {
                    // Create default config manually
                    String defaultConfig = """
                        # Speech Bubbles Configuration
                        
                        # Default settings for speech bubbles
                        defaults:
                          # Default display duration in milliseconds
                          duration: 5000
                          # Default maximum width in pixels (626 = original bubble image width)
                          maxWidth: 626
                          # Default maximum height in pixels (349 = original bubble image height)
                          maxHeight: 349
                          # Default text color (hex)
                          textColor: "#FFFFFF"
                          # Default background opacity (0.0 - 1.0)
                          backgroundOpacity: 0.9
                          # Field of view in degrees (for 3D to screen projection)
                          fov: 75.0
                        
                        # Maximum concurrent bubbles per player
                        maxBubblesPerPlayer: 10
                        
                        # Cleanup interval in seconds
                        cleanupInterval: 30
                        """;
                    Files.writeString(configPath, defaultConfig);
                    LOGGER.atInfo().log("Default config.yml created at " + configPath);
                }
            } catch (IOException e) {
                LOGGER.atWarning().log("Failed to copy default config: " + e.getMessage());
            }
        }
    }
}
