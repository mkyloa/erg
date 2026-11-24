package org.jellyfin.androidtv.ui.playback;

import org.jellyfin.androidtv.ui.playback.overlay.PlaybackInfoFormat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.DynamicsProcessing.Limiter;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.graphics.TypefaceCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.BufferLength;
import org.jellyfin.androidtv.preference.constant.ZoomMode;
import org.jellyfin.androidtv.ui.playback.mpv.MpvPlayer;
import org.jellyfin.androidtv.ui.playback.mpv.MpvSupport;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod;
import org.koin.java.KoinJavaComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.AssHandlerConfig;
import io.github.peerless2012.ass.media.factory.AssRenderersFactory;
import io.github.peerless2012.ass.media.kt.AssPlayerKt;
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory;
import io.github.peerless2012.ass.media.type.AssRenderType;
import io.github.peerless2012.ass.media.widget.AssSubtitleView;
import timber.log.Timber;

@OptIn(markerClass = UnstableApi.class)
public class VideoManager {
    private ZoomMode mZoomMode;
    private Activity mActivity;
    private Equalizer mEqualizer;
    private DynamicsProcessing mDynamicsProcessing;
    private Limiter mLimiter;
    private PlaybackControllerNotifiable mPlaybackControllerNotifiable;
    private PlaybackOverlayFragmentHelper _helper;
    public ExoPlayer mExoPlayer;
    private PlayerView mExoPlayerView;
    /** Set when the mpv engine is selected; ExoPlayer is then not created at all. */
    @Nullable
    private MpvPlayer mMpv;
    private Handler mHandler = new Handler();

    private long mMetaDuration = -1;
    private long lastExoPlayerPosition = -1;
    private boolean nightModeEnabled;

    public boolean isContracted = false;

    private final UserPreferences userPreferences = KoinJavaComponent.get(UserPreferences.class);
    private final HttpDataSource.Factory exoPlayerHttpDataSourceFactory = KoinJavaComponent.get(HttpDataSource.Factory.class);

    public VideoManager(@NonNull Activity activity, @NonNull View view, @NonNull PlaybackOverlayFragmentHelper helper) {
        mActivity = activity;
        _helper = helper;
        nightModeEnabled = userPreferences.get(UserPreferences.Companion.getAudioNightMode());

        mExoPlayerView = view.findViewById(R.id.exoPlayerView);
        // Fall back to ExoPlayer if mpv can't start (e.g. the native library fails to load)
        if (MpvSupport.isEnabled(userPreferences) && initMpv(activity)) return;

        boolean assDirectPlay = userPreferences.get(UserPreferences.Companion.getAssDirectPlay());
        AssHandler assHandler = assDirectPlay ? new AssHandler(AssRenderType.OVERLAY_OPEN_GL, new AssHandlerConfig()) : null;

        mExoPlayer = configureExoplayerBuilder(activity, assHandler).build();

        if (userPreferences.get(UserPreferences.Companion.getDebuggingEnabled())) {
            mExoPlayer.addAnalyticsListener(new EventLogger());
        }

        // Volume normalisation (audio night mode).
        if (nightModeEnabled) {
            mExoPlayer.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onAudioSessionIdChanged(AnalyticsListener.EventTime eventTime, int audioSessionId) {
                    VideoManagerHelperKt.applyAudioNightmode(audioSessionId);
                }
            });
        }

        mExoPlayerView.setPlayer(mExoPlayer);
        int strokeColor = userPreferences.get(UserPreferences.Companion.getSubtitleTextStrokeColor()).intValue();
        int textWeight = userPreferences.get(UserPreferences.Companion.getSubtitlesTextWeight());
        CaptionStyleCompat subtitleStyle = new CaptionStyleCompat(
                userPreferences.get(UserPreferences.Companion.getSubtitlesTextColor()).intValue(),
                userPreferences.get(UserPreferences.Companion.getSubtitlesBackgroundColor()).intValue(),
                Color.TRANSPARENT,
                Color.alpha(strokeColor) == 0 ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                strokeColor,
                TypefaceCompat.create(activity, Typeface.DEFAULT, textWeight, false)
        );
        mExoPlayerView.getSubtitleView().setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, userPreferences.get(UserPreferences.Companion.getSubtitlesTextSize()));
        mExoPlayerView.getSubtitleView().setBottomPaddingFraction(userPreferences.get(UserPreferences.Companion.getSubtitlesOffsetPosition()));
        mExoPlayerView.getSubtitleView().setStyle(subtitleStyle);

        if (assHandler != null) {
            assHandler.init(mExoPlayer);
            mExoPlayerView.getSubtitleView().addView(new AssSubtitleView(mActivity, assHandler));
        }

        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Timber.e("***** Got error from player");
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onError();
                stopProgressLoop();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onPrepared();
                    startProgressLoop();
                    _helper.setScreensaverLock(true);
                } else {
                    stopProgressLoop();
                    _helper.setScreensaverLock(false);
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    Timber.d("Player is buffering");
                }

                if (playbackState == Player.STATE_ENDED) {
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onCompletion();
                    stopProgressLoop();
                }
            }

            @Override
            public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
                if (mPlaybackControllerNotifiable != null) {
                    mPlaybackControllerNotifiable.onPlaybackSpeedChange(playbackParameters.speed);
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                // discontinuity for reason internal usually indicates an error, and that the player will reset to its default timestamp
                if (reason == Player.DISCONTINUITY_REASON_INTERNAL) {
                    Timber.i("Caught player discontinuity (reason internal) - oldPos: %s newPos: %s", oldPosition.positionMs, newPosition.positionMs);
                }
            }

            @Override
            public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
                Timber.d("Caught player timeline change - reason: %s", reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ? "PLAYLIST_CHANGED" : "SOURCE_UPDATE");
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                Timber.d("Tracks changed");
            }
        });
    }

    public void subscribe(@NonNull PlaybackControllerNotifiable notifier) {
        mPlaybackControllerNotifiable = notifier;
    }

    /** Replaces the ExoPlayer view with an mpv surface and routes all calls to mpv. */
    private boolean initMpv(@NonNull Activity activity) {
        ApiClient api = KoinJavaComponent.get(ApiClient.class);
        try {
        mMpv = new MpvPlayer(activity, new MpvPlayer.Listener() {
            @Override
            public void onPrepared() {
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onPrepared();
            }

            @Override
            public void onPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onPrepared();
                    startProgressLoop();
                    _helper.setScreensaverLock(true);
                } else {
                    stopProgressLoop();
                    _helper.setScreensaverLock(false);
                }
            }

            @Override
            public void onCompletion() {
                stopProgressLoop();
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onCompletion();
            }

            @Override
            public void onError(@NonNull String message) {
                Timber.e("mpv error: %s", message);
                stopProgressLoop();
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onError();
            }

            @Override
            public void onSpeedChanged(float speed) {
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onPlaybackSpeedChange(speed);
            }
        }, MpvSupport.createOptions(activity, userPreferences, api));
        } catch (Throwable e) {
            Timber.e(e, "Could not start mpv, falling back to ExoPlayer");
            mMpv = null;
            return false;
        }

        ViewGroup parent = (ViewGroup) mExoPlayerView.getParent();
        mMpv.attachTo(parent, parent.indexOfChild(mExoPlayerView), new FrameLayout.LayoutParams((FrameLayout.LayoutParams) mExoPlayerView.getLayoutParams()));
        mExoPlayerView.setVisibility(View.GONE);
        return true;
    }

    /** True when video plays through mpv instead of ExoPlayer. */
    public boolean isMpv() {
        return mMpv != null;
    }

    /** The view showing the video, for resizing. */
    private View getVideoView() {
        return mMpv != null ? mMpv.getSurfaceView() : mExoPlayerView;
    }

    /** Selects a subtitle by Jellyfin media stream index (-1 disables). mpv only. */
    public boolean selectMpvSubtitle(int index) {
        return mMpv != null && mMpv.selectSubtitle(index);
    }

    /**
     * Runs {@code callback} each time playback passes {@code positionMs}. Used for skipping intros
     * and credits; works for both engines.
     */
    public void addPositionCallback(long positionMs, @NonNull Runnable callback) {
        if (mMpv != null) {
            mMpv.addPositionCallback(positionMs, callback);
        } else if (mExoPlayer != null) {
            mExoPlayer.createMessage((type, payload) -> callback.run())
                    .setLooper(android.os.Looper.getMainLooper())
                    // Positions at 0 are never reached by ExoPlayer, so nudge them forward
                    .setPosition(Math.max(positionMs, 1))
                    .setDeleteAfterDelivery(false)
                    .send();
        }
    }

    /** Engine details for the playback info overlay. */
    public @NonNull List<String> getEngineStats() {
        if (mMpv != null) {
            List<String> lines = new ArrayList<>();
            lines.add("Player: mpv");
            lines.addAll(mMpv.stats());
            return lines;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Player: ExoPlayer");
        if (mExoPlayer == null) return lines;
        androidx.media3.common.Format video = mExoPlayer.getVideoFormat();
        if (video != null) {
            lines.add("Video: " + PlaybackInfoFormat.codec(video.sampleMimeType)
                    + (video.codecs != null ? " (" + video.codecs + ")" : "")
                    + " · " + video.width + "x" + video.height
                    + (video.frameRate > 0 ? String.format(java.util.Locale.US, " · %.3f fps", video.frameRate) : ""));
            lines.add("Color: " + PlaybackInfoFormat.color(video));
        }
        androidx.media3.common.Format audio = mExoPlayer.getAudioFormat();
        if (audio != null) {
            lines.add("Audio: " + PlaybackInfoFormat.codec(audio.sampleMimeType) + " · " + PlaybackInfoFormat.channels(audio.channelCount)
                    + (audio.sampleRate > 0 ? " · " + (audio.sampleRate / 1000) + " kHz" : ""));
        }
        long bitrate = (video != null && video.bitrate > 0 ? video.bitrate : 0) + (audio != null && audio.bitrate > 0 ? audio.bitrate : 0);
        if (bitrate > 0) lines.add(String.format(java.util.Locale.US, "Bitrate: %.1f Mbps", bitrate / 1_000_000.0));
        lines.add(String.format(java.util.Locale.US, "Buffer: %.0f s", Math.max(0, mExoPlayer.getBufferedPosition() - mExoPlayer.getCurrentPosition()) / 1000.0));
        androidx.media3.exoplayer.DecoderCounters counters = mExoPlayer.getVideoDecoderCounters();
        if (counters != null) {
            counters.ensureUpdated();
            lines.add("Dropped frames: " + counters.droppedBufferCount);
        }
        return lines;
    }

    private int determineExoPlayerExtensionRendererMode() {
        if (userPreferences.get(UserPreferences.Companion.getPreferExoPlayerFfmpeg())) {
            return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        }

        return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
    }

    /**
     * Configures Exoplayer for video playback. Initially we try with core decoders, but allow
     * ExoPlayer to silently fallback to software renderers.
     *
     * @param context The associated context
     * @return A configured builder for Exoplayer
     */
    private ExoPlayer.Builder configureExoplayerBuilder(Context context, AssHandler assHandler) {
        ExoPlayer.Builder exoPlayerBuilder = new ExoPlayer.Builder(context);
        // Erg: plays Dolby Vision profile 7 as its HDR10 base layer when the device can't decode it
        boolean doviFallback = org.jellyfin.androidtv.ui.playback.dovi.DoviSupport.useProfile7Fallback(userPreferences);
        DefaultRenderersFactory defaultRendererFactory = new org.jellyfin.androidtv.ui.playback.dovi.DoviFallbackRenderersFactory(context, doviFallback);
        defaultRendererFactory.setEnableDecoderFallback(true);
        defaultRendererFactory.setExtensionRendererMode(determineExoPlayerExtensionRendererMode());

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setAudioOffloadPreferences(new TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                        .build()
                )
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                .build()
        );
        exoPlayerBuilder.setTrackSelector(trackSelector);

        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);
        extractorsFactory.setConstantBitrateSeekingEnabled(true);
        extractorsFactory.setConstantBitrateSeekingAlwaysEnabled(true);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, exoPlayerHttpDataSourceFactory);
        if (assHandler != null) {
            AssSubtitleParserFactory assSubtitleParserFactory = new AssSubtitleParserFactory(assHandler);
            ExtractorsFactory assExtractorsFactory = AssPlayerKt.withAssMkvSupport(extractorsFactory, assSubtitleParserFactory, assHandler);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory);
            mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory);
            exoPlayerBuilder.setMediaSourceFactory(mediaSourceFactory);
            exoPlayerBuilder.setRenderersFactory(new AssRenderersFactory(assHandler, defaultRendererFactory));
        } else {
            exoPlayerBuilder.setRenderersFactory(defaultRendererFactory);
            exoPlayerBuilder.setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory));
        }

        BufferLength bufferLength = userPreferences.get(UserPreferences.Companion.getBufferLength());
        DefaultLoadControl loadControl;
        if (bufferLength == BufferLength.LARGE) {
            loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
                    .build();
        } else if (bufferLength == BufferLength.EXTRA_LARGE) {
            loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(80_000, 240_000, 5_000, 10_000)
                    .build();
        } else {
            loadControl = new DefaultLoadControl();
        }
        exoPlayerBuilder.setLoadControl(loadControl);

        exoPlayerBuilder.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);

        return exoPlayerBuilder;
    }

    public boolean isInitialized() {
        return mExoPlayer != null || mMpv != null;
    }

    public @NonNull ZoomMode getZoomMode() {
        return mZoomMode;
    }

    public void setZoom(@NonNull ZoomMode mode) {
        mZoomMode = mode;
        if (mMpv != null) {
            mMpv.setZoom(mode);
            return;
        }
        switch (mode) {
            case FIT:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case AUTO_CROP:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case STRETCH:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
        }
    }

    public void setMetaDuration(long duration) {
        mMetaDuration = duration;
    }

    public long getDuration() {
        if (mMpv != null) return mMpv.getCurrentDurationMs() > 0 ? mMpv.getCurrentDurationMs() : mMetaDuration;
        return isInitialized() && mExoPlayer.getDuration() > 0 ? mExoPlayer.getDuration() : mMetaDuration;
    }

    public long getBufferedPosition() {
        if (!isInitialized())
            return -1;

        long bufferedPosition = mMpv != null ? mMpv.getBufferedPositionMs() : mExoPlayer.getBufferedPosition();

        if (bufferedPosition > -1 && bufferedPosition < getDuration()) {
            return bufferedPosition;
        }
        return -1;
    }

    public long getCurrentPosition() {
        if (mMpv != null) return mMpv.getCurrentPositionMs();
        if (mExoPlayer == null || !isPlaying()) {
            return lastExoPlayerPosition == -1 ? 0 : lastExoPlayerPosition;
        } else {
            long mExoPlayerCurrentPosition = mExoPlayer.getCurrentPosition();
            lastExoPlayerPosition = mExoPlayerCurrentPosition;
            return mExoPlayerCurrentPosition;
        }
    }

    public boolean isPlaying() {
        if (mMpv != null) return mMpv.isPlaying();
        return mExoPlayer.isPlaying();
    }

    public void start() {
        if (mMpv != null) {
            mMpv.play();
            normalWidth = getVideoView().getLayoutParams().width;
            normalHeight = getVideoView().getLayoutParams().height;
            return;
        }
        if (mExoPlayer == null) {
            Timber.e("mExoPlayer should not be null!!");
            _helper.getFragment().closePlayer();
            return;
        }
        mExoPlayer.setPlayWhenReady(true);
        normalWidth = mExoPlayerView.getLayoutParams().width;
        normalHeight = mExoPlayerView.getLayoutParams().height;
    }

    public void play() {
        if (mMpv != null) {
            mMpv.play();
            return;
        }
        mExoPlayer.setPlayWhenReady(true);
    }

    public void pause() {
        if (mMpv != null) {
            mMpv.pause();
            return;
        }
        mExoPlayer.setPlayWhenReady(false);
    }

    public void stopPlayback() {
        if (mMpv != null) {
            mMpv.stop();
            mMpv.clearPositionCallbacks();
        }
        if (mExoPlayer != null) {
            mExoPlayer.stop();

            mExoPlayer.setTrackSelectionParameters(mExoPlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build());
        }

        stopProgressLoop();
    }

    public boolean isSeekable() {
        if (!isInitialized())
            return false;
        // mpv seeks within direct-played files and HLS transcodes alike
        if (mMpv != null) return true;

        boolean canSeek = mExoPlayer.isCurrentMediaItemSeekable();
        Timber.d("current media item is%s seekable", canSeek ? "" : " not");
        return canSeek;
    }

    public long seekTo(long pos) {
        if (!isInitialized())
            return -1;

        Timber.i("Exo length in seek is: %d", getDuration());
        if (mMpv != null) {
            mMpv.seekTo(pos);
            return pos;
        }
        mExoPlayer.seekTo(pos);
        return pos;
    }

    private int getSubtitleSelectionFlags(MediaStream mediaStream) {
        int flags = 0;
        if (mediaStream.isDefault()) flags &= C.SELECTION_FLAG_DEFAULT;
        if (mediaStream.isForced()) flags &= C.SELECTION_FLAG_FORCED;
        return flags;
    }

    public void setMediaStreamInfo(ApiClient api, StreamInfo streamInfo) {
        String path = streamInfo.getMediaUrl();
        if (path == null) {
            Timber.w("Video path is null cannot continue");
            return;
        }
        Timber.i("Video path set to: %s", path);

        if (mMpv != null) {
            List<MpvPlayer.ExternalSubtitle> externalSubtitles = new ArrayList<>();
            for (MediaStream mediaStream : streamInfo.getMediaSource().getMediaStreams()) {
                if (mediaStream.getType() != MediaStreamType.SUBTITLE) continue;
                if (mediaStream.getDeliveryMethod() != SubtitleDeliveryMethod.EXTERNAL || mediaStream.getDeliveryUrl() == null) continue;
                String url = api.createUrl(mediaStream.getDeliveryUrl(), Collections.emptyMap(), Collections.emptyMap(), true);
                externalSubtitles.add(new MpvPlayer.ExternalSubtitle(mediaStream.getIndex(), url, mediaStream.getDisplayTitle(), mediaStream.getLanguage()));
            }
            // Track selection happens through the controller once the file is open
            mMpv.load(path, 0, externalSubtitles, null, null);
            return;
        }

        try {
            // Add external subtitles
            List<MediaItem.SubtitleConfiguration> subtitleConfigurations = new ArrayList<>();
            for (MediaStream mediaStream : streamInfo.getMediaSource().getMediaStreams()) {
                if (mediaStream.getType() != MediaStreamType.SUBTITLE) continue;

                if (mediaStream.getDeliveryMethod() == SubtitleDeliveryMethod.EXTERNAL) {
                    Uri subtitleUri = Uri.parse(api.createUrl(mediaStream.getDeliveryUrl(), Collections.emptyMap(), Collections.emptyMap(), true));
                    MediaItem.SubtitleConfiguration subtitleConfiguration = new MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                            .setId("JF_EXTERNAL:" + String.valueOf(mediaStream.getIndex()))
                            .setMimeType(VideoManagerHelperKt.getSubtitleMediaStreamCodec(mediaStream))
                            .setLanguage(mediaStream.getLanguage())
                            .setLabel(mediaStream.getDisplayTitle())
                            .setSelectionFlags(getSubtitleSelectionFlags(mediaStream))
                            .build();
                    Timber.i("Adding subtitle track %s of type %s", subtitleConfiguration.uri, subtitleConfiguration.mimeType);
                    subtitleConfigurations.add(subtitleConfiguration);
                }
            }

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(Uri.parse(path))
                    .setSubtitleConfigurations(subtitleConfigurations)
                    .build();

            mExoPlayer.setMediaItem(mediaItem);
            mExoPlayer.prepare();
        } catch (IllegalStateException e) {
            Timber.e(e, "Unable to set video path.  Probably backing out.");
        }
    }

    private int offsetStreamIndex(int index, boolean adjustByAdding, @Nullable List<org.jellyfin.sdk.model.api.MediaStream> allStreams) {
        if (index < 0 || allStreams == null)
            return -1;

        // translate player track index to/from Jellyfin MediaStream index to account for external tracks
        // being in the MediaStream tracks list but not in a player's track list
        //
        // use adjustByAdding=true to translate player-id -> MediaStream-id, false for the other direction
        //
        // use indexStartsAtOne=true when the player's tracks list uses indexes/IDs starting at 1
        // MediaStream indexes/IDs start at 0

        for (org.jellyfin.sdk.model.api.MediaStream stream : allStreams) {
            if (!stream.isExternal())
                break;
            index += adjustByAdding ? 1 : -1;
        }

        return index < 0 || index > allStreams.size() ? -1 : index;
    }

    public int getExoPlayerTrack(@Nullable org.jellyfin.sdk.model.api.MediaStreamType streamType, @Nullable List<org.jellyfin.sdk.model.api.MediaStream> allStreams) {
        if (!isInitialized() || streamType == null || allStreams == null)
            return -1;
        if (mMpv != null) {
            if (streamType == org.jellyfin.sdk.model.api.MediaStreamType.AUDIO) return mMpv.selectedIndex("audio");
            if (streamType == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE) return mMpv.selectedIndex("sub");
            return -1;
        }
        if (streamType != org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE && streamType != org.jellyfin.sdk.model.api.MediaStreamType.AUDIO)
            return -1;

        int chosenTrackType = streamType == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;

        int matchedIndex = -2;
        Tracks exoTracks = mExoPlayer.getCurrentTracks();
        for (Tracks.Group groupInfo : exoTracks.getGroups()) {
            if (matchedIndex > -2)
                break;
            // Group level information.
            @C.TrackType int trackType = groupInfo.getType();
            TrackGroup group = groupInfo.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (trackType == chosenTrackType) {
                    if (groupInfo.isTrackSelected(i)) {
                        // we found the track, set to -1 first to handle failed int parsing
                        matchedIndex = -1;
                        int id;
                        try {
                            if (group.id.contains(":")) {
                                id = Integer.parseInt(group.id.split(":")[1]);
                            } else {
                                id = Integer.parseInt(group.id);
                            }
                        } catch (NumberFormatException e) {
                            Timber.w("failed to parse group ID [%s]", group.id);
                            break;
                        }
                        matchedIndex = id;
                        break;
                    }
                }
            }
        }

        // offset the stream index to account for external streams
        int exoTrackID = offsetStreamIndex(matchedIndex, true, allStreams);
        if (exoTrackID < 0)
            return -1;

        Timber.d("re-retrieved exoplayer track index %s", exoTrackID);
        return exoTrackID;
    }

    public boolean setExoPlayerTrack(int index, @Nullable org.jellyfin.sdk.model.api.MediaStreamType streamType, @Nullable List<org.jellyfin.sdk.model.api.MediaStream> allStreams) {
        if (!isInitialized() || allStreams == null || allStreams.isEmpty() || streamType != org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE && streamType != org.jellyfin.sdk.model.api.MediaStreamType.AUDIO)
            return false;
        if (mMpv != null) {
            return streamType == org.jellyfin.sdk.model.api.MediaStreamType.AUDIO ? mMpv.selectAudio(index) : mMpv.selectSubtitle(index);
        }

        int chosenTrackType = streamType == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;

        // Make sure the index is present
        Optional<MediaStream> candidateOptional = allStreams.stream().filter(stream -> stream.getIndex() == index && !stream.isExternal() && stream.getType() == streamType).findFirst();
        if (!candidateOptional.isPresent()) return false;

        int exoTrackID = offsetStreamIndex(index, false, allStreams);
        if (exoTrackID < 0)
            return false;

        // print the streams for debugging
        for (MediaStream stream : allStreams) {
            Timber.d("MediaStream track %s type %s label %s codec %s isExternal %s", stream.getIndex(), stream.getType(), stream.getTitle(), stream.getCodec(), stream.isExternal());
        }

        // design choices for exoplayer track selection overrides:
        // * build upon the prior parameters so we can mix overrides of different track types without erasing priors
        //
        // * for subtitles (not currently used) - use setDisabledTrackTypes to disable or enable exoplayer handing subtitles
        //   if we want most formats to be handled by the external subtitle handler (which has adjustable size, background), we leave sub track selection disabled
        //   if we decide to use exoplayer to render a specific subtitle format, allow subtitle track selection and restrict selection to the chosen group

        Tracks exoTracks = mExoPlayer.getCurrentTracks();
        TrackGroup matchedGroup = null;
        for (Tracks.Group groupInfo : exoTracks.getGroups()) {
            // Group level information.
            @C.TrackType int trackType = groupInfo.getType();
            TrackGroup group = groupInfo.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                // Individual track information.
                boolean isSupported = groupInfo.isTrackSupported(i);
                boolean isSelected = groupInfo.isTrackSelected(i);
                Format trackFormat = group.getFormat(i);

                Timber.i("track %s group %s/%s trackType %s label %s mime %s isSelected %s isSupported %s",
                        trackFormat.id, i + 1, group.length, trackType, trackFormat.label, trackFormat.sampleMimeType, isSelected, isSupported);

                if (trackType != chosenTrackType)
                    continue;

                int id;
                try {
                    if (group.id.contains(":")) {
                        id = Integer.parseInt(group.id.split(":")[1]);
                    } else {
                        id = Integer.parseInt(group.id);
                    }
                    if (id != exoTrackID)
                        continue;
                } catch (NumberFormatException e) {
                    Timber.w("failed to parse group ID [%s]", group.id);
                    continue;
                }

                if (!groupInfo.isTrackSupported(i)) {
                    Timber.d("track is not compatible");
                    return false;
                }

                if (groupInfo.isTrackSelected(i)) {
                    Timber.d("track is already selected");
                    return true;
                }

                Timber.i("matched exoplayer track %s to mediaStream track %s", trackFormat.id, index);
                matchedGroup = group;
            }
        }

        if (matchedGroup == null)
            return false;

        try {
            TrackSelectionParameters.Builder mExoPlayerSelectionParams = mExoPlayer.getTrackSelectionParameters().buildUpon();
            mExoPlayerSelectionParams.setOverrideForType(new TrackSelectionOverride(matchedGroup, 0));
            mExoPlayer.setTrackSelectionParameters(mExoPlayerSelectionParams.build());
        } catch (Exception e) {
            Timber.w("Error setting track selection");
            return false;
        }
        return true;
    }

    public float getPlaybackSpeed() {
        if (!isInitialized()) {
            return 1.0f;
        } else if (mMpv != null) {
            return mMpv.getPlaybackSpeed();
        } else {
            return mExoPlayer.getPlaybackParameters().speed;
        }
    }

    public void setPlaybackSpeed(float speed) {
        if (speed < 0.25) {
            Timber.w("Invalid playback speed requested: %f", speed);
            return;
        }
        Timber.d("Setting playback speed: %f", speed);

        if (mMpv != null) {
            mMpv.setSpeed(speed);
            return;
        }
        mExoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
    }

    public void destroy() {
        mPlaybackControllerNotifiable = null;
        stopPlayback();
        releasePlayer();
    }

    private void releasePlayer() {
        _helper.setScreensaverLock(false);
        if (mMpv != null) {
            mMpv.release();
            mMpv = null;
        }
        if (mExoPlayer != null) {
            mExoPlayerView.setPlayer(null);
            mExoPlayer.release();
            mExoPlayer = null;
        }
    }

    int normalWidth;
    int normalHeight;

    public void contractVideo(int height) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getVideoView().getLayoutParams();
        if (isContracted) return;

        int sw = mActivity.getWindow().getDecorView().getWidth();
        int sh = mActivity.getWindow().getDecorView().getHeight();
        float ar = (float) sw / sh;
        lp.height = height;
        lp.width = (int) Math.ceil(height * ar);
        lp.rightMargin = ((lp.width - normalWidth) / 2) - 110;
        lp.bottomMargin = ((lp.height - normalHeight) / 2) - 50;

        getVideoView().setLayoutParams(lp);
        getVideoView().invalidate();

        isContracted = true;
    }

    public void setVideoFullSize(boolean force) {
        if (normalHeight == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getVideoView().getLayoutParams();
        if (force) {
            lp.height = -1;
            lp.width = -1;
        } else {
            lp.height = normalHeight;
            lp.width = normalWidth;
        }

        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        getVideoView().setLayoutParams(lp);
        getVideoView().invalidate();

        isContracted = false;
    }

    private Runnable progressLoop;

    private void startProgressLoop() {
        stopProgressLoop();
        progressLoop = new Runnable() {
            @Override
            public void run() {
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onProgress();
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler.post(progressLoop);
    }

    private void stopProgressLoop() {
        if (progressLoop != null) {
            mHandler.removeCallbacks(progressLoop);
        }
    }
}
