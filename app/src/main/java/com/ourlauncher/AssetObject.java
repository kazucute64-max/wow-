package com.ourlauncher;

/**
 * One entry from a version's asset index — a single sound, texture,
 * language file, etc. The hash doubles as both its SHA-1 checksum and
 * its address on Mojang's CDN (first 2 hex chars as a subfolder).
 */
public class AssetObject {
    public final String hash;
    public final long size;

    public AssetObject(String hash, long size) {
        this.hash = hash;
        this.size = size;
    }

    public String downloadUrl() {
        return "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
    }

    public String relativeStoragePath() {
        return hash.substring(0, 2) + "/" + hash;
    }
}
