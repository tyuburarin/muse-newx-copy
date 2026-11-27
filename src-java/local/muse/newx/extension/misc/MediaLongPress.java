package local.muse.newx.extension.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import local.muse.newx.extension.utils.NewXUtils;

/**
 * Handles a long-press on a post's image or video in NewX.
 *
 * The patch hooks the head of the post event handler
 * {@code Lcom/x/urt/items/post/h5;->invoke}: when the event is a
 * {@code DidLongPressMediaContent} (Lcom/x/urt/items/post/k4;), its field
 * {@code a} is the exact media item that was pressed (image / video / GIF
 * model), so the direct URL comes from {@link MediaUrlExtractor} applied to
 * that single item — no post-level index resolution. The patch returns from
 * the handler immediately after this call, so the native bottom sheet
 * (Save / Copy Photo / ...) does not open; a tap is a different event type
 * and is untouched.
 *
 * What the long-press does with the URL is selectable at patch time through
 * the Morphe option {@code mediaLongPressAction} on the "NewX: Copy media
 * URL" patch: {@code copy} (clipboard, the original behaviour),
 * {@code download} (enqueue in DownloadManager into the public Downloads
 * folder), or {@code choose} (v0.2.6: a native chooser dialog asks
 * 「リンクをコピー」 / 「ダウンロード」 on every long-press — the legacy
 * {@code both} value, which used to run copy AND download together, is
 * accepted as an alias for {@code choose}). The patch rewrites
 * {@link #longPressAction()} to return the mapped constant (0 / 1 / 2)
 * when the bundle is applied; the post-menu "メディアURLをコピー" item is
 * always copy-only and does not consult this value.
 *
 * There is no presenter and no View on this path, so the base Context comes
 * from the current Application (see {@link NewXUtils#applicationContext()}).
 * The chooser additionally needs an Activity window token: the current
 * foreground Activity is resolved through ActivityThread reflection
 * ({@link #currentActivity()}). When no usable Activity exists, the
 * chooser falls back to copying the URL, with a toast saying so.
 */
public final class MediaLongPress {
    private static final String TAG = "MuseNewXCopy";

    static final int ACTION_COPY = 0;
    static final int ACTION_DOWNLOAD = 1;
    static final int ACTION_CHOOSE = 2;

    private MediaLongPress() {
    }

    /**
     * Patch-time stub: the media patch replaces this body with a const
     * return of the value mapped from the mediaLongPressAction option
     * (0=copy, 1=download, 2=choose). The stock body is copy, so an
     * unrewritten extension still behaves as the original feature.
     */
    static int longPressAction() {
        return ACTION_COPY;
    }

    public static void onMediaLongPress(Object event) {
        Context context = null;
        try {
            Object media = fieldValue(event, "a");
            if (media == null) return;

            List<String> urls = MediaUrlExtractor.extractUrls(Collections.singletonList(media));
            context = NewXUtils.applicationContext();
            if (context == null) return;
            if (urls.isEmpty()) {
                NewXUtils.showToastShort(context, "メディアURLがありません");
                return;
            }

            int action = longPressAction();
            if (action == ACTION_DOWNLOAD) {
                performDownload(context, urls);
            } else if (action == ACTION_CHOOSE) {
                showChooser(context, urls);
            } else {
                performCopy(context, urls);
            }
        } catch (Exception exception) {
            Log.e(TAG, "Failed to handle media long-press", exception);
            NewXUtils.showToastShort(context, "メディアURLがありません");
        }
    }

    /** Copies the URLs and confirms with a toast. */
    private static void performCopy(Context context, List<String> urls) {
        copyToClipboard(context, urls);
        NewXUtils.showToastShort(context, "メディアURLをコピーしました");
    }

    /**
     * Enqueues the downloads and confirms with a toast. When DownloadManager
     * is unavailable or rejects every URL, falls back to copying so the
     * long-press still does something, and says so in the toast.
     */
    private static void performDownload(Context context, List<String> urls) {
        if (enqueueDownloads(context, urls)) {
            NewXUtils.showToastShort(context, "ダウンロードを開始しました");
        } else {
            copyToClipboard(context, urls);
            NewXUtils.showToastShort(context, "ダウンロードできないためURLをコピーしました");
        }
    }

    /**
     * The "choose" mode: a native AlertDialog with 「リンクをコピー」 /
     * 「ダウンロード」, cancelable (tapping outside or Back dismisses it and
     * does nothing). The dialog needs an Activity context; the long-press
     * hook point has no View, so the foreground Activity is resolved via
     * {@link #currentActivity()}. With no usable Activity, the URL is
     * copied instead and the toast says the chooser could not be shown.
     */
    private static void showChooser(Context fallbackContext, List<String> urls) {
        Activity activity = currentActivity();
        if (activity == null) {
            performCopy(fallbackContext, urls);
            NewXUtils.showToastShort(
                    fallbackContext, "選択画面を表示できないためURLをコピーしました");
            return;
        }
        Runnable show = () -> {
            try {
                new AlertDialog.Builder(activity)
                        .setItems(
                                new CharSequence[] {"リンクをコピー", "ダウンロード"},
                                (dialog, which) -> {
                                    if (which == 0) {
                                        performCopy(activity, urls);
                                    } else if (which == 1) {
                                        performDownload(activity, urls);
                                    }
                                })
                        .setCancelable(true)
                        .show();
            } catch (Exception exception) {
                Log.e(TAG, "Failed to show the long-press chooser", exception);
                performCopy(fallbackContext, urls);
                NewXUtils.showToastShort(
                        fallbackContext, "選択画面を表示できないためURLをコピーしました");
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            new Handler(Looper.getMainLooper()).post(show);
        }
    }

    /**
     * The current foreground Activity, via ActivityThread reflection:
     * {@code currentActivityThread().mActivities} holds one record per
     * activity; the record whose {@code paused} flag is false (and whose
     * activity is neither finishing nor destroyed) is the resumed one.
     * Returns null when no such activity exists.
     */
    private static Activity currentActivity() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentThread = activityThread.getMethod("currentActivityThread");
            Object thread = currentThread.invoke(null);
            if (thread == null) return null;
            Field activitiesField = findField(activityThread, "mActivities");
            if (activitiesField == null) return null;
            Object records = activitiesField.get(thread);
            if (!(records instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) records).values()) {
                if (record == null) continue;
                Field activityField = findField(record.getClass(), "activity");
                Field pausedField = findField(record.getClass(), "paused");
                if (activityField == null || pausedField == null) continue;
                Object candidate = activityField.get(record);
                if (!(candidate instanceof Activity activity)) continue;
                if (pausedField.getBoolean(record)) continue;
                if (activity.isFinishing() || activity.isDestroyed()) continue;
                return activity;
            }
            return null;
        } catch (Exception exception) {
            Log.e(TAG, "Failed to resolve the current activity", exception);
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    private static void copyToClipboard(Context context, List<String> urls) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("メディアURL", String.join("\n", urls)));
    }

    /** Enqueues every URL in DownloadManager; true if at least one was accepted. */
    private static boolean enqueueDownloads(Context context, List<String> urls) {
        try {
            DownloadManager downloads =
                    (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloads == null) return false;
            boolean any = false;
            for (String url : urls) {
                try {
                    String fileName = fileNameFor(url);
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setTitle(fileName);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);
                    if (downloads.enqueue(request) != -1L) any = true;
                } catch (Exception exception) {
                    Log.e(TAG, "Failed to enqueue media download: " + url, exception);
                }
            }
            return any;
        } catch (Exception exception) {
            Log.e(TAG, "DownloadManager unavailable", exception);
            return false;
        }
    }

    /** Last path segment of the URL (query stripped), with a sane fallback name. */
    private static String fileNameFor(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.isEmpty() || !name.contains(".")) {
            String extension = path.contains(".mp4") || url.contains("video")
                    ? ".mp4"
                    : ".jpg";
            name = "media_" + System.currentTimeMillis() + extension;
        }
        return name;
    }

    private static Object fieldValue(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equals(name)) continue;
                try {
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (Exception exception) {
                    return null;
                }
            }
        }
        return null;
    }
}
