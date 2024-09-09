package net.bali0531.ultracoins;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class UltraCoinsPlaceholder extends PlaceholderExpansion {

    private final Ultracoins plugin;

    public UltraCoinsPlaceholder(Ultracoins plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getAuthor() {
        return "Bali0531";
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ultracoins";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true; // This is required or else PlaceholderAPI will unregister the Expansion on reload
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            if (params.equalsIgnoreCase("coins_player")) {
                return String.valueOf(plugin.getCoins(player.getPlayer()));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error processing placeholder request: " + e.getMessage());
        }
        return null; // Placeholder is unknown by the Expansion
    }
}