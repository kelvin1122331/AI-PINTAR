package id.omni.floatai;

final class StableTranslation {
    private int[] shown;
    private int[] empty;
    private long emptySince;
    private int emptyReads;
    private java.util.List<TranslationOverlay.Label> visible = java.util.Collections.emptyList();

    void presented(int[] scene) {
        shown = scene;
        moved();
    }

    java.util.List<TranslationOverlay.Label> present(int[] scene, java.util.List<TranslationOverlay.Label> readings) {
        java.util.List<TranslationOverlay.Label> next = readings;
        if (shown != null && !ScreenScene.changed(shown, scene) && !visible.isEmpty() && readings.size() < 90) {
            var combined = new java.util.ArrayList<>(readings);
            for (var old : visible) {
                if (combined.size() == 90) break;
                if (combined.stream().noneMatch(fresh -> sameArea(old, fresh))) combined.add(old);
            }
            next = combined;
        }
        presented(scene);
        visible = next;
        return next;
    }

    private static boolean sameArea(TranslationOverlay.Label a, TranslationOverlay.Label b) {
        int horizontal = Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x());
        int vertical = Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
        if (horizontal > 0 && vertical * 3 >= Math.min(a.height(), b.height())) return true;
        return !a.original().isEmpty() && a.original().equalsIgnoreCase(b.original())
            && Math.abs(a.x() - b.x()) <= 22 && Math.abs(a.y() - b.y()) <= 22;
    }

    void moved() {
        empty = null;
        emptySince = 0;
        emptyReads = 0;
    }

    boolean needsConfirmation(int[] scene) {
        return shown != null && ScreenScene.materiallyDifferent(shown, scene);
    }

    boolean confirmEmpty(int[] scene, long now, long hold) {
        if (!needsConfirmation(scene)) {
            moved();
            return false;
        }
        if (ScreenScene.changed(empty, scene)) {
            empty = scene;
            emptySince = now;
            emptyReads = 1;
            return false;
        }
        emptyReads++;
        return emptyReads >= 3 && now - emptySince >= hold;
    }

    void reset() {
        shown = null;
        visible = java.util.Collections.emptyList();
        moved();
    }
}
