package com.ourlauncher;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.google.android.material.chip.Chip;

import java.util.List;

/** Renders each VersionEntry as a rounded Material card with a type chip. */
public class VersionAdapter extends ArrayAdapter<VersionEntry> {

    /** Callback fired when a row is tapped — attached directly to the row view. */
    public interface OnVersionClickListener {
        void onVersionClick(VersionEntry entry);
    }

    /** Cheap existence check only (not full SHA-1 verification) — purely for the visual indicator. */
    public interface DownloadedChecker {
        boolean isDownloaded(String versionId);
    }

    private final OnVersionClickListener clickListener;
    private final DownloadedChecker downloadedChecker;

    public VersionAdapter(Context context, List<VersionEntry> versions,
                           OnVersionClickListener clickListener, DownloadedChecker downloadedChecker) {
        super(context, 0, versions);
        this.clickListener = clickListener;
        this.downloadedChecker = downloadedChecker;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(getContext()).inflate(R.layout.item_version, parent, false);
        }

        VersionEntry entry = getItem(position);

        TextView idText = row.findViewById(R.id.item_version_id);
        Chip typeChip = row.findViewById(R.id.item_version_type);
        TextView downloadedIndicator = row.findViewById(R.id.item_version_downloaded);

        if (entry != null) {
            idText.setText(entry.id);
            typeChip.setText(entry.type);

            boolean downloaded = downloadedChecker != null && downloadedChecker.isDownloaded(entry.id);
            downloadedIndicator.setVisibility(downloaded ? View.VISIBLE : View.GONE);

            // Attaching the click listener directly to the row guarantees it
            // fires even if the card intercepts touch before ListView's own
            // onItemClickListener would have seen it.
            row.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onVersionClick(entry);
            });
        }

        return row;
    }
}
