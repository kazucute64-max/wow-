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

    private final OnVersionClickListener clickListener;

    public VersionAdapter(Context context, List<VersionEntry> versions, OnVersionClickListener clickListener) {
        super(context, 0, versions);
        this.clickListener = clickListener;
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

        if (entry != null) {
            idText.setText(entry.id);
            typeChip.setText(entry.type);

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
