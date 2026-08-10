package com.ourlauncher;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A "local" (offline-mode) account — no Microsoft login involved. The UUID
 * is generated the same way Minecraft's own offline mode does: hashing
 * "OfflinePlayer:<username>" as a UUID name. This is a standard, well-known
 * mechanism (vanilla Minecraft itself uses it for LAN/offline play, and
 * every major third-party launcher supports it for testing) — it doesn't
 * bypass account ownership for anything that requires a real login, it's
 * just a stable, deterministic local identity.
 */
public class LocalAccount {

    public final String username;
    public final String uuid; // no dashes, matching Mojang's usual UUID string format elsewhere in this app

    private LocalAccount(String username, String uuid) {
        this.username = username;
        this.uuid = uuid;
    }

    /** Validates and builds a local account from a typed username, or returns null if invalid. */
    public static LocalAccount create(String rawUsername) {
        if (rawUsername == null) return null;
        String username = rawUsername.trim();
        if (!isValidUsername(username)) return null;

        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        String uuidNoDashes = offlineUuid.toString().replace("-", "");

        return new LocalAccount(username, uuidNoDashes);
    }

    /** Minecraft usernames: 3-16 characters, letters/digits/underscore only. */
    public static boolean isValidUsername(String username) {
        return username != null && username.matches("^[a-zA-Z0-9_]{3,16}$");
    }
}
