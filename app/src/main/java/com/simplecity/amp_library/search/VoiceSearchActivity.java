package com.simplecity.amp_library.search;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.widget.Toast;
import com.annimon.stream.Stream;
import com.simplecity.amp_library.ShuttleApplication;
import com.simplecity.amp_library.dagger.module.ActivityModule;
import com.simplecity.amp_library.model.Album;
import com.simplecity.amp_library.model.AlbumArtist;
import com.simplecity.amp_library.playback.MediaManager;
import com.simplecity.amp_library.playback.QueueManager;
import com.simplecity.amp_library.ui.activities.BaseActivity;
import com.simplecity.amp_library.ui.activities.MainActivity;
import com.simplecity.amp_library.utils.ComparisonUtils;
import com.simplecity.amp_library.utils.DataManager;
import com.simplecity.amp_library.utils.LogUtils;
import com.simplecity.amp_library.utils.SettingsManager;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import java.util.Collections;
import java.util.Locale;
import javax.inject.Inject;
import kotlin.Unit;

import static com.simplecity.amp_library.utils.StringUtils.containsIgnoreCase;

public class VoiceSearchActivity extends BaseActivity {

    private static final String TAG = "VoiceSearchActivity";

    private String filterString;

    private Intent intent;

    private int position = -1;

    @Inject
    MediaManager mediaManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ShuttleApplication.getInstance().getAppComponent().plus(new ActivityModule(this)).inject(this);

        intent = getIntent();

        filterString = intent.getStringExtra(SearchManager.QUERY);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        super.onServiceConnected(name, service);
        if (intent != null && intent.getAction() != null && intent.getAction().equals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)) {
            String mediaFocus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS);
            if (mediaFocus != null && mediaFocus.compareTo(MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE) == 0) {
                searchAndStartPlaylist();
            } else {
                searchAndPlaySongs();
            }
        }
    }

    @SuppressLint("CheckResult")
    private void searchAndStartPlaylist() {
        String query;
        if (intent.hasExtra("android.intent.extra.playlist")) {
            query = intent.getStringExtra("android.intent.extra.playlist");
        } else {
            query = intent.getStringExtra(SearchManager.QUERY);
        }
        String finalQuery = query;
        boolean shuffle;
        if (intent.hasExtra("com.simplecity.amp_library.shuffle")) {
            shuffle = Boolean.parseBoolean(intent.getStringExtra("com.simplecity.amp_library.shuffle"));
        } else {
            shuffle = SettingsManager.getInstance().getRememberShuffle() && mediaManager.getShuffleMode() == QueueManager.ShuffleMode.ON;
        }
        DataManager.getInstance().getPlaylistsRelay()
                .first(Collections.emptyList())
                .flatMapObservable(Observable::fromIterable)
                .filter(playlist -> playlist.name.toLowerCase(Locale.getDefault()).contains(finalQuery.toLowerCase()))
                .flatMap(playlist -> playlist.getSongsObservable())
                .firstOrError()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(songs -> {
                    if (songs != null) {
                        mediaManager.setShuffleMode(shuffle ? QueueManager.ShuffleMode.ON : QueueManager.ShuffleMode.OFF);
                        if (shuffle) {
                            mediaManager.shuffleAll(songs, message -> {
                                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                                return Unit.INSTANCE;
                            });
                        } else {
                            mediaManager.playAll(songs, position, true, message -> {
                                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                                return Unit.INSTANCE;
                            });
                        }
                        startActivity(new Intent(this, MainActivity.class));
                    }
                    finish();
                }, error -> {
                    LogUtils.logException(TAG, "Error attempting to playAll()", error);
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
    }

    private void searchAndPlaySongs() {

        DataManager.getInstance().getAlbumArtistsRelay()
                .first(Collections.emptyList())
                .flatMapObservable(Observable::fromIterable)
                .filter(albumArtist -> albumArtist.name.toLowerCase(Locale.getDefault()).contains(filterString.toLowerCase()))
                .flatMapSingle(AlbumArtist::getSongsSingle)
                .map(songs -> {
                    Collections.sort(songs, (a, b) -> a.getAlbumArtist().compareTo(b.getAlbumArtist()));
                    Collections.sort(songs, (a, b) -> a.getAlbum().compareTo(b.getAlbum()));
                    Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.track, b.track));
                    Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.discNumber, b.discNumber));
                    return songs;
                });

        //Search for album-artists, albums & songs matching our filter. Then, create an Observable emitting List<Song> for each type of result.
        //Then we concat the results, and return the first one which is non-empty. Order is important here, we want album-artist first, if it's
        //available, then albums, then songs.
        Observable.concat(
                //If we have an album artist matching our query, then play the songs by that album artist
                DataManager.getInstance().getAlbumArtistsRelay()
                        .first(Collections.emptyList())
                        .flatMapObservable(Observable::fromIterable)
                        .filter(albumArtist -> albumArtist.name.toLowerCase(Locale.getDefault()).contains(filterString.toLowerCase()))
                        .flatMapSingle(AlbumArtist::getSongsSingle)
                        .map(songs -> {
                            Collections.sort(songs, (a, b) -> a.getAlbumArtist().compareTo(b.getAlbumArtist()));
                            Collections.sort(songs, (a, b) -> a.getAlbum().compareTo(b.getAlbum()));
                            Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.track, b.track));
                            Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.discNumber, b.discNumber));
                            return songs;
                        }),
                //If we have an album matching our query, then play the songs from that album
                DataManager.getInstance().getAlbumsRelay()
                        .first(Collections.emptyList())
                        .flatMapObservable(Observable::fromIterable)
                        .filter(album -> containsIgnoreCase(album.name, filterString)
                                || containsIgnoreCase(album.name, filterString)
                                || (Stream.of(album.artists).anyMatch(artist -> containsIgnoreCase(artist.name, filterString)))
                                || containsIgnoreCase(album.albumArtistName, filterString))
                        .flatMapSingle(Album::getSongsSingle)
                        .map(songs -> {
                            Collections.sort(songs, (a, b) -> a.getAlbum().compareTo(b.getAlbum()));
                            Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.track, b.track));
                            Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.discNumber, b.discNumber));
                            return songs;
                        }),
                //If have a song, play that song, as well as others from the same album.
                DataManager.getInstance().getSongsRelay()
                        .first(Collections.emptyList())
                        .flatMapObservable(Observable::fromIterable)
                        .filter(song -> containsIgnoreCase(song.name, filterString)
                                || containsIgnoreCase(song.albumName, filterString)
                                || containsIgnoreCase(song.artistName, filterString)
                                || containsIgnoreCase(song.albumArtistName, filterString))
                        .flatMapSingle(song -> song.getAlbum().getSongsSingle()
                                .map(songs -> {
                                    Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.track, b.track));
                                    Collections.sort(songs, (a, b) -> ComparisonUtils.compareInt(a.discNumber, b.discNumber));
                                    position = songs.indexOf(song);
                                    return songs;
                                }))
        )
                .filter(songs -> !songs.isEmpty())
                .firstOrError()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(songs -> {
                    if (songs != null) {
                        mediaManager.playAll(songs, position, true, message -> {
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                            return Unit.INSTANCE;
                        });
                        startActivity(new Intent(this, MainActivity.class));
                    }
                    finish();
                }, error -> {
                    LogUtils.logException(TAG, "Error attempting to playAll()", error);
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
    }

    @Override
    protected String screenName() {
        return TAG;
    }
}