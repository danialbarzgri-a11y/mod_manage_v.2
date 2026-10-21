package com.sovereigncore;

import java.util.Locale;

/** Immutable, serializable Minecraft dimension position. */
final class SavedPos {
    final String dimension;
    final double x;
    final double y;
    final double z;
    final float yaw;
    final float pitch;

    SavedPos(String dimension, double x, double y, double z, float yaw, float pitch) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    String encode() {
        return dimension + "," + x + "," + y + "," + z + "," + yaw + "," + pitch;
    }

    String shortDisplay() {
        return dimension + " @ " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }

    static SavedPos parse(String raw) {
        if (raw == null) return null;
        String[] bits = raw.split(",", 6);
        if (bits.length != 6) return null;
        try {
            String dimension = bits[0].trim();
            if (dimension.isEmpty()) return null;
            double x = Double.parseDouble(bits[1]);
            double y = Double.parseDouble(bits[2]);
            double z = Double.parseDouble(bits[3]);
            float yaw = Float.parseFloat(bits[4]);
            float pitch = Float.parseFloat(bits[5]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < -4096 || y > 4096) return null;
            return new SavedPos(dimension, x, y, z, yaw, pitch);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
