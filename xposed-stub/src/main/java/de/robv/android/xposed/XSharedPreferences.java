package de.robv.android.xposed;

import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * SharedPreferences implementation that reads another package's preference file.
 * Requires the module manifest meta-data {@code xposedsharedprefs=true}.
 * Stub for compileOnly use (api 93).
 */
public class XSharedPreferences implements SharedPreferences {
    public XSharedPreferences(String packageName) {
        throw new UnsupportedOperationException("stub");
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        throw new UnsupportedOperationException("stub");
    }

    public XSharedPreferences(File prefFile) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean makeWorldReadable() {
        throw new UnsupportedOperationException("stub");
    }

    public File getFile() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean hasFileChanged() {
        throw new UnsupportedOperationException("stub");
    }

    public void reload() {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean isFileWorldReadable() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Map<String, ?> getAll() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public String getString(String key, String defValue) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getInt(String key, int defValue) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public long getLong(String key, long defValue) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public float getFloat(String key, float defValue) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public boolean contains(String key) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException("stub");
    }
}
