package com.ourlauncher;

/** One entry from Mojang's public version_manifest_v2.json */
public class VersionEntry {
    public final String id;
    public final String type;   // "release" or "snapshot"
    public final String url;    // per-version metadata URL

    public VersionEntry(String id, String type, String url) {
        this.id = id;
        this.type = type;
        this.url = url;
    }

    @Override
    public String toString() {
        return id + "  [" + type + "]";
    }
}
