package com.ourlauncher;

/** One entry from a version's "libraries" array in its manifest JSON. */
public class LibraryEntry {
    public final String url;   // download URL for this jar
    public final String path;  // relative path, e.g. "com/mojang/blah/1.0/blah.jar"
    public final String sha1;
    public final long size;

    public LibraryEntry(String url, String path, String sha1, long size) {
        this.url = url;
        this.path = path;
        this.sha1 = sha1;
        this.size = size;
    }
}
