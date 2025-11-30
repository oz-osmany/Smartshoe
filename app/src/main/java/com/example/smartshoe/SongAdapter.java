package com.example.smartshoe;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

public class SongAdapter extends ArrayAdapter<Song> {

    private final LayoutInflater inflater;

    public SongAdapter(@NonNull Context context, @NonNull List<Song> songs) {
        super(context, 0, songs);
        inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_song_card, parent, false);
            holder = new ViewHolder();
            holder.imgCover = convertView.findViewById(R.id.imgCover);
            holder.textTitle = convertView.findViewById(R.id.textSongTitle);
            holder.textSubtitle = convertView.findViewById(R.id.textSongSubtitle);
            holder.btnPlay = convertView.findViewById(R.id.btnPlay);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Song song = getItem(position);
        if (song != null) {
            holder.textTitle.setText(song.title);
            holder.textSubtitle.setText(song.subtitle);

            holder.btnPlay.setOnClickListener(v -> {
                if (getContext() instanceof PracticeActivity) {
                    ((PracticeActivity) getContext()).onSongPlayClicked(song);
                }
            });

            convertView.setOnClickListener(v -> {
                if (getContext() instanceof PracticeActivity) {
                    ((PracticeActivity) getContext()).onSongSelected(song);
                }
            });
        }

        return convertView;
    }

    static class ViewHolder {
        ImageView imgCover;
        TextView textTitle;
        TextView textSubtitle;
        ImageButton btnPlay;
    }
}
