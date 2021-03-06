package com.liskovsoft.smartyoutubetv2.common.app.models.playback.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.mediaserviceinterfaces.MediaItemManager;
import com.liskovsoft.mediaserviceinterfaces.MediaService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.PlayerEventListenerHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controller.PlaybackUiController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.managers.SuggestionsLoader.MetadataListener;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.autoframerate.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager.OnSelectSubtitleStyle;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager.SubtitleStyle;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.youtubeapi.service.YouTubeMediaService;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;

public class PlayerUiManager extends PlayerEventListenerHelper implements MetadataListener {
    private static final String TAG = PlayerUiManager.class.getSimpleName();
    private static final long SUGGESTIONS_RESET_TIMEOUT_MS = 500;
    private static final int SUBTITLE_STYLES_ID = 45;
    private final Handler mHandler;
    private final MediaItemManager mMediaItemManager;
    private boolean mEngineReady;
    private boolean mDebugViewEnabled;
    private PlayerData mPlayerData;
    private final Runnable mSuggestionsResetHandler = () -> getController().resetSuggestedPosition();
    private final Runnable mUiAutoHideHandler = () -> {
        if (getController().isPlaying()) {
            if (!getController().isSuggestionsShown()) { // don't hide when suggestions is shown
                getController().showControls(false);
            }
        } else {
            // in seeking state? doing recheck...
            disableUiAutoHideTimeout();
            enableUiAutoHideTimeout();
        }
    };

    public PlayerUiManager() {
        mHandler = new Handler(Looper.getMainLooper());

        MediaService service = YouTubeMediaService.instance();
        mMediaItemManager = service.getMediaItemManager();
    }

    @Override
    public void onInitDone() {
        AppSettingsPresenter.instance(getActivity()).setPlayerUiManager(this);
        mPlayerData = PlayerData.instance(getActivity());
    }

    @Override
    public void onControlsShown(boolean shown) {
        disableUiAutoHideTimeout();

        if (shown) {
            enableUiAutoHideTimeout();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        disableUiAutoHideTimeout();
        disableSuggestionsResetTimeout();

        if (KeyHelpers.isBackKey(keyCode)) {
            enableSuggestionsResetTimeout();
        } else if (KeyHelpers.isMenuKey(keyCode)) {
            getController().showControls(!getController().isControlsShown());
        } else if (KeyHelpers.isConfirmKey(keyCode) && !getController().isControlsShown()) {
            switch (mPlayerData.getOKButtonBehavior()) {
                case PlayerData.ONLY_UI:
                    getController().showControls(true);
                    return true; // don't show ui
                case PlayerData.UI_AND_PAUSE:
                    // NOP
                    break;
                case PlayerData.ONLY_PAUSE:
                    getController().setPlay(!getController().isPlaying());
                    return true; // don't show ui
            }
        } else if (KeyHelpers.isStopKey(keyCode)) {
            getController().exit();
            return true;
        }

        enableUiAutoHideTimeout();

        return false;
    }

    @Override
    public void onChannelClicked() {
        ChannelPresenter.instance(getActivity()).openChannel(getController().getVideo());
    }

    @Override
    public void onSubtitlesClicked() {
        List<FormatItem> subtitleFormats = getController().getSubtitleFormats();

        String subtitlesCategoryTitle = getActivity().getString(R.string.subtitle_category_title);
        String subtitleFormatsTitle = getActivity().getString(R.string.subtitle_language);

        AppSettingsPresenter settingsPresenter = AppSettingsPresenter.instance(getActivity());

        settingsPresenter.clear();

        settingsPresenter.appendRadioCategory(subtitleFormatsTitle,
                UiOptionItem.from(subtitleFormats,
                        option -> getController().selectFormat(UiOptionItem.toFormat(option)),
                        getActivity().getString(R.string.subtitles_disabled)));

        OptionCategory category = createSubtitleStylesCategory(
                getActivity(), mPlayerData,
                style -> getController().setSubtitleStyle(style));

        settingsPresenter.appendRadioCategory(category.title, category.options);

        settingsPresenter.showDialog(subtitlesCategoryTitle);
    }

    @Override
    public void onPlaylistAddClicked() {
        VideoMenuPresenter mp = VideoMenuPresenter.instance(getActivity());

        mp.showShortMenu(getController().getVideo());
    }

    @Override
    public void onVideoStatsClicked(boolean enabled) {
        mDebugViewEnabled = enabled;
        getController().showDebugView(enabled);
    }

    @Override
    public void onEngineInitialized() {
        mEngineReady = true;
    }

    @Override
    public void onVideoLoaded(Video item) {
        // Next lines on engine initialized stage cause other listeners to disappear.
        getController().showDebugView(mDebugViewEnabled);
        getController().setDebugButtonState(mDebugViewEnabled);

        if (mPlayerData.isSeekPreviewEnabled()) {
            getController().loadStoryboard();
        }
    }

    @Override
    public void onEngineReleased() {
        Log.d(TAG, "Engine released. Disabling all callbacks...");
        mEngineReady = false;

        disposeTimeouts();
    }

    //@Override
    //public void onRepeatModeClicked(int modeIndex) {
    //    //getController().setRepeatMode(modeIndex);
    //}

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        getController().setLikeButtonState(metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_LIKE);
        getController().setDislikeButtonState(metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_DISLIKE);
        getController().setSubscribeButtonState(metadata.isSubscribed());
    }

    @Override
    public void onSuggestionItemLongClicked(Video item) {
        VideoMenuPresenter.instance(getActivity()).showMenu(item);
    }

    @Override
    public void onSubscribeClicked(boolean subscribed) {
        if (subscribed) {
            callMediaItemObservable(mMediaItemManager::subscribeObserve);
        } else {
            callMediaItemObservable(mMediaItemManager::unsubscribeObserve);
        }

        showBriefInfo(subscribed);
    }

    private void showBriefInfo(boolean subscribed) {
        if (subscribed) {
            MessageHelpers.showMessage(getActivity(), R.string.subscribed_to_channel);
        } else {
            MessageHelpers.showMessage(getActivity(), R.string.unsubscribed_to_channel);
        }
    }

    @Override
    public void onThumbsDownClicked(boolean thumbsDown) {
        if (thumbsDown) {
            callMediaItemObservable(mMediaItemManager::setDislikeObserve);
        } else {
            callMediaItemObservable(mMediaItemManager::removeDislikeObserve);
        }
    }

    @Override
    public void onThumbsUpClicked(boolean thumbsUp) {
        if (thumbsUp) {
            callMediaItemObservable(mMediaItemManager::setLikeObserve);
        } else {
            callMediaItemObservable(mMediaItemManager::removeLikeObserve);
        }
    }

    @Override
    public void onVideoSpeedClicked() {
        List<OptionItem> items = new ArrayList<>();

        // suppose live stream if buffering near the end
        // boolean isStream = Math.abs(player.getDuration() - player.getCurrentPosition()) < 10_000;
        intSpeedItems(items, new float[]{0.25f, 0.5f, 0.75f, 1.0f, 1.1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3.0f});

        AppSettingsPresenter settingsPresenter = AppSettingsPresenter.instance(getActivity());
        settingsPresenter.clear();
        settingsPresenter.appendRadioCategory(getActivity().getString(R.string.video_speed), items);
        settingsPresenter.showDialog();
    }

    @Override
    public void onSearchClicked() {
        SearchPresenter.instance(getActivity()).startSearch(null);
    }

    private void intSpeedItems(List<OptionItem> items, float[] speedValues) {
        for (float speed : speedValues) {
            items.add(UiOptionItem.from(
                    String.valueOf(speed),
                    optionItem -> getController().setSpeed(speed),
                    getController().getSpeed() == speed));
        }
    }

    public void disableUiAutoHideTimeout() {
        Log.d(TAG, "Stopping auto hide ui timer...");
        mHandler.removeCallbacks(mUiAutoHideHandler);
    }

    public void enableUiAutoHideTimeout() {
        Log.d(TAG, "Starting auto hide ui timer...");
        if (mEngineReady && mPlayerData.getUIHideTimoutSec() > 0) {
            mHandler.postDelayed(mUiAutoHideHandler, mPlayerData.getUIHideTimoutSec() * 1_000);
        }
    }

    private void disableSuggestionsResetTimeout() {
        Log.d(TAG, "Stopping reset position timer...");
        mHandler.removeCallbacks(mSuggestionsResetHandler);
    }

    private void enableSuggestionsResetTimeout() {
        Log.d(TAG, "Starting reset position timer...");
        if (mEngineReady) {
            mHandler.postDelayed(mSuggestionsResetHandler, SUGGESTIONS_RESET_TIMEOUT_MS);
        }
    }

    private void disposeTimeouts() {
        disableUiAutoHideTimeout();
        disableSuggestionsResetTimeout();
    }

    private void callMediaItemObservable(MediaItemObservable callable) {
        Video video = getController().getVideo();

        if (video == null || video.mediaItem == null) {
            Log.e(TAG, "Seems that video isn't initialized yet. Cancelling...");
            return;
        }

        Observable<Void> observable = callable.call(video.mediaItem);

        observable
                .subscribeOn(Schedulers.newThread())
                .subscribe();
    }

    private interface MediaItemObservable {
        Observable<Void> call(MediaItem item);
    }

    public static OptionCategory createSubtitleStylesCategory(Context context, PlayerData playerData) {
        return createSubtitleStylesCategory(context, playerData, style -> {});
    }

    private static OptionCategory createSubtitleStylesCategory(Context context, PlayerData playerData, OnSelectSubtitleStyle onSelectSubtitleStyle) {
        List<SubtitleStyle> subtitleStyles = playerData.getSubtitleStyles();
        
        String subtitleStyleTitle = context.getString(R.string.subtitle_style);

        return OptionCategory.from(SUBTITLE_STYLES_ID, subtitleStyleTitle, fromSubtitleStyles(context, playerData, subtitleStyles, onSelectSubtitleStyle));
    }

    private static List<OptionItem> fromSubtitleStyles(Context context, PlayerData playerData, List<SubtitleStyle> subtitleStyles, OnSelectSubtitleStyle onSelectSubtitleStyle) {
        List<OptionItem> styleOptions = new ArrayList<>();

        for (SubtitleStyle subtitleStyle : subtitleStyles) {
            styleOptions.add(UiOptionItem.from(
                    context.getString(subtitleStyle.nameResId),
                    option -> {
                        playerData.setSubtitleStyle(subtitleStyle);
                        onSelectSubtitleStyle.onSelectSubtitleStyle(subtitleStyle);
                    },
                    subtitleStyle.equals(playerData.getSubtitleStyle())));
        }

        return styleOptions;
    }
}
