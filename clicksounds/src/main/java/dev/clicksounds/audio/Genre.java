package dev.clicksounds.audio;

/** Kinds of click in a clickpack, each with its own pair of folders. */
public enum Genre {
    NORMAL("clicks", "releases", "Clicks", "Used for normal clicking"),
    SOFT("softclicks", "softreleases", "Soft clicks", "Optional: used when you click fast (spam)"),
    HARD("hardclicks", "hardreleases", "Hard clicks", "Optional: used for the first click after a pause");

    public final String clicksFolder, releasesFolder, title, description;

    Genre(String clicksFolder, String releasesFolder, String title, String description) {
        this.clicksFolder = clicksFolder;
        this.releasesFolder = releasesFolder;
        this.title = title;
        this.description = description;
    }
}
