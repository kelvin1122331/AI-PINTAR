package id.omni.floatai;

final class ScreenScene {
    static boolean changed(int[] before, int[] after) {
        if (before == null || before.length != after.length) return true;
        int changed = 0;
        int threshold = Math.max(6, after.length / 220);
        for (int i = 0; i < after.length; i++) {
            int a = before[i], b = after[i];
            int distance = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
            if (distance >= 53 && ++changed >= threshold) return true;
        }
        return false;
    }

    static int restore(int composite, int paint, float windowOpacity) {
        float alpha = ((paint >>> 24) / 255f) * windowOpacity;
        if (alpha < 0.003f) return composite;
        float original = 1f - alpha;
        int r = channel(composite, paint, 16, alpha, original);
        int g = channel(composite, paint, 8, alpha, original);
        int b = channel(composite, paint, 0, alpha, original);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int channel(int composite, int paint, int shift, float alpha, float original) {
        int value = Math.round((((composite >> shift) & 255) - ((paint >> shift) & 255) * alpha) / original);
        return Math.max(0, Math.min(255, value));
    }

    private ScreenScene() {}
}
