package com.phlox.simpleserver.middleware.intentsenders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.phlox.server.utils.MultiMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class IntentSender {
    public static final String FIELD_URL_PATH = "url_path";
    public static final String FIELD_TARGET = "target";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_ACTION = "action";
    public static final String FIELD_PACKAGE = "package";
    public static final String FIELD_CLASS = "class";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_MIME_TYPE = "mime_type";
    public static final String FIELD_FLAGS = "flags";
    public static final String FIELD_EXTRAS = "extras";
    public static final String FIELD_EXTRA_KEY = "key";
    public static final String FIELD_EXTRA_VALUE = "value";
    public static final String FIELD_EXTRA_TYPE = "type";

    public enum IntentTarget { BROADCAST, ORDERED_BROADCAST, SERVICE, ACTIVITY }
    public enum IntentExtraType { AUTO, STRING, BOOLEAN, INTEGER, LONG, FLOAT, DOUBLE, STRING_ARRAY }

    public static class IntentExtra {
        public @NonNull String key;
        public @NonNull String value;
        public @NonNull IntentExtraType type;

        public IntentExtra(@NonNull String key, @NonNull String value, @NonNull IntentExtraType type) {
            this.key = key;
            this.value = value;
            this.type = type;
        }

        public IntentExtra(@NonNull String key, @NonNull String value) {
            this.key = key;
            this.value = value;
            this.type = IntentExtraType.AUTO;
        }
    }

    public @NonNull String urlPath;
    public @NonNull IntentTarget target = IntentTarget.BROADCAST;
    public @Nullable String category;
    public @Nullable String action;
    public @Nullable String packageName;
    public @Nullable String clazz;
    public @Nullable String data;
    public @Nullable String mimeType;
    public int flags = 0;
    public @NonNull List<IntentExtra> extras = new ArrayList<>();

    public IntentSender(@NonNull String urlPath) {
        this.urlPath = urlPath;
    }

    public @NonNull JSONObject serialize() throws JSONException {
        JSONObject json = new JSONObject()
                .put(FIELD_URL_PATH, this.urlPath)
                .put(FIELD_TARGET, this.target.name())
                .put(FIELD_CATEGORY, this.category)
                .put(FIELD_ACTION, this.action)
                .put(FIELD_PACKAGE, this.packageName)
                .put(FIELD_CLASS, this.clazz)
                .put(FIELD_DATA, this.data)
                .put(FIELD_MIME_TYPE, this.mimeType)
                .put(FIELD_FLAGS, this.flags);
        JSONArray extrasArray = new JSONArray();
        for (IntentExtra extra : this.extras) {
            JSONObject extraJson = new JSONObject()
                .put(FIELD_EXTRA_KEY, extra.key)
                .put(FIELD_EXTRA_VALUE, extra.value)
                .put(FIELD_EXTRA_TYPE, extra.type.name());
            extrasArray.put(extraJson);
        }
        json.put(FIELD_EXTRAS, extrasArray);
        return json;
    }

    public static @NonNull IntentSender deserialize(@NonNull JSONObject object) throws JSONException {
        IntentSender intentSender = new IntentSender(object.getString(FIELD_URL_PATH));
        intentSender.target = IntentTarget.valueOf(object.getString(FIELD_TARGET));
        intentSender.category = object.optString(FIELD_CATEGORY);
        intentSender.action = object.optString(FIELD_ACTION);
        intentSender.packageName = object.optString(FIELD_PACKAGE);
        intentSender.clazz = object.optString(FIELD_CLASS);
        intentSender.data = object.optString(FIELD_DATA);
        intentSender.mimeType = object.optString(FIELD_MIME_TYPE);
        intentSender.flags = object.optInt(FIELD_FLAGS);
        intentSender.extras = new ArrayList<>();
        JSONArray extrasArray = object.optJSONArray(FIELD_EXTRAS);
        for (int i = 0; i < Objects.requireNonNull(extrasArray).length(); i++) {
            JSONObject extraJson = extrasArray.getJSONObject(i);
            String typeStr = extraJson.optString(FIELD_EXTRA_TYPE);
            IntentExtraType type;
            if (typeStr == null || typeStr.isEmpty()) {
                type = IntentExtraType.AUTO;
            } else {
                try {
                    type = IntentExtraType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    // Unknown type, default to AUTO
                    type = IntentExtraType.AUTO;
                }
            }
            IntentExtra extra = new IntentExtra(
                    extraJson.optString(FIELD_EXTRA_KEY),
                    extraJson.optString(FIELD_EXTRA_VALUE),
                    type
            );
            intentSender.extras.add(extra);
        }
        return intentSender;
    }

    public void send(Context context) {
        send(context, null, null);
    }

    public void send(Context context, @Nullable MultiMap<String, String> params, BroadcastReceiver resultReceiver) {
        Intent intent = new Intent();
        intent.setAction(this.action);
        if (this.category != null && !this.category.isEmpty()) {
            intent.addCategory(this.category);
        }
        if (this.packageName != null && this.clazz != null && !this.packageName.isEmpty() && !this.clazz.isEmpty()) {
            intent.setClassName(this.packageName, this.clazz);
        } else if (this.packageName != null && !this.packageName.isEmpty()) {
            intent.setPackage(this.packageName);
        }
        if (this.data != null && !this.data.isEmpty()) {
            intent.setData(Uri.parse(this.data));
        }
        if (this.mimeType != null && !this.mimeType.isEmpty()) {
            intent.setType(this.mimeType);
        }
        if (this.flags != 0) {
            intent.setFlags(this.flags);
        }
        for (IntentExtra extra : this.extras) {
            // Resolve parameter substitution if value starts with ":"
            String value = extra.value;
            if (params != null && value != null && value.startsWith(":")) {
                String paramName = value.substring(1);
                String paramValue = params.get(paramName);
                if (paramValue != null) {
                    value = paramValue;
                }
                // If param not found, keep original value (with ":")
            }
            
            IntentExtraType type = extra.type;
            if (type == IntentExtraType.AUTO) {
                type = detectExtraType(value);
            }
            
            switch (type) {
                case STRING:
                    intent.putExtra(extra.key, value);
                    break;
                case BOOLEAN:
                    intent.putExtra(extra.key, Boolean.parseBoolean(value));
                    break;
                case INTEGER:
                    intent.putExtra(extra.key, Integer.parseInt(value));
                    break;
                case LONG:
                    intent.putExtra(extra.key, Long.parseLong(value));
                    break;
                case FLOAT:
                    intent.putExtra(extra.key, Float.parseFloat(value));
                    break;
                case DOUBLE:
                    intent.putExtra(extra.key, Double.parseDouble(value));
                    break;
                case STRING_ARRAY:
                    intent.putExtra(extra.key, value.split(","));
                    break;
                default:
                    throw new IllegalArgumentException("Invalid intent extra type: " + type);
            }
        }
        switch (this.target) {
            case BROADCAST:
                context.sendBroadcast(intent);
                break;
            case ORDERED_BROADCAST:
                if (resultReceiver != null) {
                    context.sendOrderedBroadcast(intent, null, resultReceiver,
                            null, 0, null, null);
                } else {
                    context.sendOrderedBroadcast(intent, null);
                }
                break;
            case SERVICE:
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                break;
            case ACTIVITY:
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                break;
        }
    }

    private static IntentExtraType detectExtraType(@NonNull String value) {
        if (value == null || value.isEmpty()) {
            return IntentExtraType.STRING;
        }

        String trimmed = value.trim();

        // Check for boolean first (exact match, case-insensitive)
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return IntentExtraType.BOOLEAN;
        }

        // Check for string array (contains comma)
        if (trimmed.contains(",")) {
            return IntentExtraType.STRING_ARRAY;
        }

        // Try to parse as integer
        try {
            Integer.parseInt(trimmed);
            return IntentExtraType.INTEGER;
        } catch (NumberFormatException e) {
            // Not an integer, continue
        }

        // Try to parse as long
        try {
            Long.parseLong(trimmed);
            return IntentExtraType.LONG;
        } catch (NumberFormatException e) {
            // Not a long, continue
        }

        // Try to parse as double (handles both float and double ranges)
        try {
            double d = Double.parseDouble(trimmed);
            // Check if it has decimal point or scientific notation
            boolean hasDecimalOrScientific = trimmed.contains(".") || 
                                            trimmed.contains("e") || trimmed.contains("E") ||
                                            trimmed.contains("d") || trimmed.contains("D") ||
                                            trimmed.contains("f") || trimmed.contains("F");
            
            if (hasDecimalOrScientific) {
                // Check if it fits in float range (with some margin for precision)
                if (Math.abs(d) <= Float.MAX_VALUE && (d == 0 || Math.abs(d) >= Float.MIN_NORMAL)) {
                    return IntentExtraType.FLOAT;
                } else {
                    return IntentExtraType.DOUBLE;
                }
            }
            // If it's a whole number that parses as double but not long, 
            // it's a very large integer - represent as double
            if (d == Math.floor(d)) {
                return IntentExtraType.DOUBLE;
            }
        } catch (NumberFormatException e) {
            // Not a number, fall through to string
        }

        // Default to string
        return IntentExtraType.STRING;
    }
}
