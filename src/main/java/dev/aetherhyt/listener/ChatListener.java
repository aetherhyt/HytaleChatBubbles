package dev.aetherhyt.listener;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.aetherhyt.ChatBubbles;
import dev.aetherhyt.config.ChatBubblesConfig;
import dev.aetherhyt.manager.ChatBubblesManager;

import java.util.Collection;
import java.util.UUID;

public class ChatListener {

    private final ChatBubbles plugin;
    private final ChatBubblesManager bubbleManager;

    public ChatListener(ChatBubbles plugin, ChatBubblesManager bubbleManager) {
        this.plugin = plugin;
        this.bubbleManager = bubbleManager;
    }

    public void onPlayerChat(PlayerChatEvent event) {
        System.out.println("[ChatBubbles] Event received");
        PlayerRef sender = event.getSender();
        if (sender == null) return;
        
        System.out.println("[ChatBubbles] Sender found: " + sender.getUsername());

        String message = event.getContent();
        if (message == null || message.trim().isEmpty()) return;

        UUID senderUuid = sender.getUuid();
        Vector3d senderPos = getPlayerPosition(sender);

        if (senderPos == null) return;

        ChatBubblesConfig config = plugin.getConfig();
        int maxDistance = config.getMaxDistance();
        double maxDistSq = maxDistance * maxDistance;

        Collection<PlayerRef> onlinePlayers = Universe.get().getPlayers();

        for (PlayerRef target : onlinePlayers) {
            Vector3d targetPos = getPlayerPosition(target);
            if (targetPos == null) continue;

            double dx = senderPos.getX() - targetPos.getX();
            double dy = senderPos.getY() - targetPos.getY();
            double dz = senderPos.getZ() - targetPos.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= maxDistSq) {
                boolean result = bubbleManager.showBubble(senderUuid, target.getUuid(), message, null);
                System.out.println("[ChatBubbles] Show to " + target.getUuid() + " (dist=" + Math.sqrt(distSq) + "): " + result);
            }
        }
    }

    private Vector3d getPlayerPosition(PlayerRef player) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) return null;

            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            
            if (transform != null) {
                return transform.getPosition();
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }
}
