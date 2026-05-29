package pub.lantian.symfoniumlyricprovider;

import android.app.Application;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.proify.lyricon.lyric.model.RichLyricLine;
import io.github.proify.lyricon.lyric.model.Song;
import io.github.proify.lyricon.provider.LyriconFactory;
import io.github.proify.lyricon.provider.LyriconProvider;
import io.github.proify.lyricon.provider.ProviderConstants;
import io.github.proify.lyricon.provider.RemotePlayer;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "SymfoniumLyricProvider";
    private static final String TARGET_PACKAGE = "app.symfonik.music.player";
    private static final String PROVIDER_PACKAGE = "pub.lantian.symfoniumlyricprovider";

    private static LyriconProvider provider;
    private static String trackKey;
    private static String currentId;
    private static String currentTitle;
    private static String currentArtist;
    private static long currentDuration;
    private static List<RichLyricLine> currentLyrics;
    private static String lastLyricsSignature;

    private static final class LyricContainerData {
        final List<?> lines;
        final Object signature;

        LyricContainerData(List<?> lines, Object signature) {
            this.lines = lines;
            this.signature = signature;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName) || !TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        log("loading hooks for " + lpparam.packageName);
        hookApplication(
                lpparam.classLoader,
                lpparam.appInfo != null ? lpparam.appInfo.className : null
        );
        hookMediaSession();
        hookSymfoniumLyrics(
                lpparam.classLoader,
                lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null
        );
    }

    private static void hookApplication(ClassLoader classLoader, String applicationClassName) {
        String className = normalizeApplicationClassName(applicationClassName);
        try {
            Class<?> applicationClass = !isBlank(className)
                    ? XposedHelpers.findClass(className, classLoader)
                    : Application.class;
            XposedBridge.hookAllMethods(
                    applicationClass,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            initProvider((Application) param.thisObject);
                        }
                    }
            );
        } catch (Throwable t) {
            log("failed to hook manifest Application.onCreate", t);
            hookFrameworkApplication();
        }
    }

    private static String normalizeApplicationClassName(String className) {
        if (isBlank(className)) {
            return className;
        }
        if (className.startsWith(".")) {
            return TARGET_PACKAGE + className;
        }
        if (className.indexOf('.') >= 0) {
            return className;
        }
        return TARGET_PACKAGE + "." + className;
    }

    private static void hookFrameworkApplication() {
        try {
            XposedBridge.hookAllMethods(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Application) {
                                initProvider((Application) param.thisObject);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            log("failed to hook framework Application.onCreate", t);
        }
    }

    private static void hookMediaSession() {
        try {
            XposedBridge.hookAllMethods(
                    MediaSession.class,
                    "setMetadata",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (arg instanceof MediaMetadata) {
                                onMetadata((MediaMetadata) arg);
                            }
                        }
                    }
            );

            XposedBridge.hookAllMethods(
                    MediaSession.class,
                    "setPlaybackState",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (arg instanceof PlaybackState) {
                                onPlaybackState((PlaybackState) arg);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            log("failed to hook MediaSession", t);
        }
    }

    private static void hookSymfoniumLyrics(ClassLoader classLoader, String apkPath) {
        /*
         * Symfonium's lyric classes are obfuscated, so class names like lr5/jr5/ir5
         * are intentionally not used here. Instead, every app dex class is scanned
         * and only classes with the same structural shape as the lyric container are
         * hooked:
         *
         * - a small concrete class
         * - exactly one List field, expected to hold lyric lines
         * - several boolean state flags, such as synchronized/has-cues markers
         * - one String field, usually a lyric hash/signature
         * - a constructor that accepts the line list
         *
         * Future obfuscation can rename all classes and fields, but this shape tends
         * to remain stable as long as the underlying lyric model stays equivalent.
         */
        int hooked = 0;
        for (String className : enumerateClassNames(classLoader, apkPath)) {
            Class<?> candidate = loadClass(classLoader, className);
            if (candidate == null || !isPotentialLyricsContainerClass(candidate)) {
                continue;
            }

            try {
                XposedBridge.hookAllConstructors(candidate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (hasTrackContext()) {
                            onLyricsObject(param.thisObject, param.thisObject.getClass().getName() + ".constructor");
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("failed to hook structural lyric candidate " + className, t);
            }
        }
        log("hooked " + hooked + " structural lyric candidate(s)");
    }

    private static Set<String> enumerateClassNames(ClassLoader classLoader, String apkPath) {
        /*
         * Two sources are used for class discovery. appInfo.sourceDir covers the
         * installed APK path, while the ClassLoader pathList covers split dex entries
         * and loader-specific dex files. The LinkedHashSet keeps the scan stable and
         * avoids duplicate hooks when both sources expose the same class.
         */
        LinkedHashSet<String> classNames = new LinkedHashSet<>();
        if (!isBlank(apkPath)) {
            addDexEntries(apkPath, classNames);
        }
        addClassLoaderDexEntries(classLoader, classNames);
        return classNames;
    }

    private static void addDexEntries(String dexPath, Set<String> classNames) {
        DexFile dexFile = null;
        try {
            dexFile = new DexFile(dexPath);
            addDexEntries(dexFile, classNames);
        } catch (Throwable t) {
            log("failed to enumerate apk dex " + dexPath, t);
        } finally {
            if (dexFile != null) {
                try {
                    dexFile.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void addClassLoaderDexEntries(ClassLoader classLoader, Set<String> classNames) {
        try {
            Object pathList = XposedHelpers.getObjectField(classLoader, "pathList");
            Object dexElements = XposedHelpers.getObjectField(pathList, "dexElements");
            if (!(dexElements instanceof Object[])) {
                return;
            }

            for (Object element : (Object[]) dexElements) {
                Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                if (dexFile instanceof DexFile) {
                    addDexEntries((DexFile) dexFile, classNames);
                }
            }
        } catch (Throwable t) {
            log("failed to enumerate classloader dex entries", t);
        }
    }

    private static void addDexEntries(DexFile dexFile, Set<String> classNames) {
        Enumeration<String> entries = dexFile.entries();
        while (entries.hasMoreElements()) {
            String className = entries.nextElement();
            if (shouldScanClassName(className)) {
                classNames.add(className);
            }
        }
    }

    private static boolean shouldScanClassName(String className) {
        // Keep the expensive structural scan focused on Symfonium/app classes.
        return !className.startsWith("android.")
                && !className.startsWith("androidx.")
                && !className.startsWith("com.google.")
                && !className.startsWith("com.squareup.")
                && !className.startsWith("dalvik.")
                && !className.startsWith("io.github.proify.")
                && !className.startsWith("java.")
                && !className.startsWith("javax.")
                && !className.startsWith("kotlin.")
                && !className.startsWith("kotlinx.")
                && !className.startsWith("okhttp3.")
                && !className.startsWith("okio.")
                && !className.startsWith("org.")
                && !className.startsWith("pub.lantian.");
    }

    private static Class<?> loadClass(ClassLoader classLoader, String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isPotentialLyricsContainerClass(Class<?> candidate) {
        /*
         * This filter is deliberately based on field/constructor shape instead of
         * symbols. It should match Symfonium's current Lyrics object but reject most
         * unrelated data classes before we hook constructors.
         */
        int modifiers = candidate.getModifiers();
        if (candidate.isAnnotation()
                || candidate.isAnonymousClass()
                || candidate.isArray()
                || candidate.isEnum()
                || candidate.isInterface()
                || candidate.isPrimitive()
                || Modifier.isAbstract(modifiers)) {
            return false;
        }

        List<Field> fields = instanceFields(candidate);
        int listFields = 0;
        int booleanFields = 0;
        int stringFields = 0;
        for (Field field : fields) {
            Class<?> type = field.getType();
            if (List.class.isAssignableFrom(type)) {
                listFields++;
            } else if (type == boolean.class || type == Boolean.class) {
                booleanFields++;
            } else if (type == String.class) {
                stringFields++;
            }
        }

        return fields.size() <= 8
                && listFields == 1
                && booleanFields >= 2
                && booleanFields <= 4
                && stringFields >= 1
                && hasListConstructor(candidate);
    }

    private static boolean hasListConstructor(Class<?> candidate) {
        // The lyric container is built from the parsed line list.
        for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 1 && List.class.isAssignableFrom(parameterTypes[0])) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrackContext() {
        return !isBlank(currentId) || !isBlank(currentTitle) || !isBlank(currentArtist);
    }

    private static void initProvider(Application app) {
        if (provider != null) {
            return;
        }

        String processName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? Application.getProcessName()
                : TARGET_PACKAGE;
        provider = LyriconFactory.INSTANCE.createProvider(
                app,
                PROVIDER_PACKAGE,
                TARGET_PACKAGE,
                null,
                null,
                processName,
                null,
                ProviderConstants.SYSTEM_UI_PACKAGE_NAME
        );
        provider.getPlayer().setDisplayTranslation(true);
        provider.register();
        sendCurrentSong();
        log("provider initialized");
    }

    private static void onMetadata(MediaMetadata metadata) {
        try {
            String title = firstNonBlank(
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            );
            String artist = firstNonBlank(
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR),
                    metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER)
            );
            String id = firstNonBlank(
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    metadata.getDescription() != null ? metadata.getDescription().getMediaId() : null
            );
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String nextTrackKey = firstNonBlank(id, title + "\n" + artist + "\n" + duration);

            if (!Objects.equals(trackKey, nextTrackKey)) {
                currentLyrics = null;
                lastLyricsSignature = null;
            }

            trackKey = nextTrackKey;
            currentId = id;
            currentTitle = title;
            currentArtist = artist;
            currentDuration = duration;
            sendCurrentSong();
        } catch (Throwable t) {
            log("failed to process MediaMetadata", t);
        }
    }

    private static void onPlaybackState(PlaybackState state) {
        try {
            RemotePlayer player = player();
            if (player != null) {
                player.setPlaybackState(state);
            }
        } catch (Throwable t) {
            log("failed to process PlaybackState", t);
        }
    }

    private static void onLyricsObject(Object lyricsObject, String source) {
        if (lyricsObject == null) {
            return;
        }

        try {
            LyricContainerData container = readLyricContainer(lyricsObject);
            if (container == null) {
                return;
            }

            String signature = LyricLines.signature(container.signature, container.lines, currentDuration);
            if (Objects.equals(signature, lastLyricsSignature)) {
                return;
            }

            List<RichLyricLine> converted = LyricLines.convert(container.lines, currentDuration);
            if (converted.isEmpty()) {
                return;
            }

            currentLyrics = converted;
            lastLyricsSignature = signature;
            sendCurrentSong();
            log("sent " + converted.size() + " lyric lines from " + source);
        } catch (Throwable t) {
            log("failed to process Symfonium lyrics", t);
        }
    }

    private static LyricContainerData readLyricContainer(Object lyricsObject) {
        /*
         * Once a structural candidate is constructed, validate the actual instance.
         * The container is accepted only if one reflected List field contains line-
         * shaped objects. Any nonblank String field is treated as a reusable lyric
         * signature; if it disappears or changes type after obfuscation, a signature
         * is rebuilt from line/cue contents later.
         */
        Object signature = null;
        List<Field> fields = instanceFields(lyricsObject.getClass());
        for (Field field : fields) {
            Object value = fieldValue(field, lyricsObject);
            if (signature == null && value instanceof String && !isBlank((String) value)) {
                signature = value;
            }
        }

        for (Field field : fields) {
            Object value = fieldValue(field, lyricsObject);
            if (value instanceof List && LyricLines.isLineList((List<?>) value, currentDuration)) {
                return new LyricContainerData((List<?>) value, signature);
            }
        }
        return null;
    }

    private static void sendCurrentSong() {
        RemotePlayer player = player();
        if (player == null) {
            return;
        }
        if (isBlank(currentId) && isBlank(currentTitle) && isBlank(currentArtist)
                && (currentLyrics == null || currentLyrics.isEmpty())) {
            return;
        }

        Song song = new Song(currentId, currentTitle, currentArtist, currentDuration, null, currentLyrics);
        player.setSong(song);
    }

    private static RemotePlayer player() {
        return provider != null ? provider.getPlayer() : null;
    }

    private static List<Field> instanceFields(Class<?> type) {
        // Reflection is centralized so inherited instance fields are handled uniformly.
        ArrayList<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Object fieldValue(Field field, Object instance) {
        try {
            return field.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable throwable) {
        XposedBridge.log(TAG + ": " + message + "\n" + Log.getStackTraceString(throwable));
    }
}
