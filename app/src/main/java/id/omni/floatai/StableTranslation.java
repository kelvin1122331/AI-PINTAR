package id.omni.floatai;

final class StableTranslation {
    private int[] shown;
    private int[] empty;
    private long emptySince;
    private int emptyReads;

    void presented(int[] scene) {
        shown = scene;
        moved();
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
        moved();
    }
}
