package id.omni.floatai;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class QuizAccessibility extends AccessibilityService {
    static final String CHROME = "com.android.chrome";
    private static final String[] HOSTS = {"quizizz.com", "wayground.com", "kahoot.it", "kahoot.com",
        "forms.google.com", "forms.office.com", "forms.microsoft.com", "socrative.com", "nearpod.com",
        "blooket.com", "wordwall.net"};
    static QuizAccessibility connected;
    record Question(String page, String text, List<String> options, List<AccessibilityNodeInfo> buttons) {
        String identity() { return page + "\n" + text + "\n" + options; }
    }

    @Override protected void onServiceConnected() { connected = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    private AccessibilityNodeInfo browser() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (!window.isActive() || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && CHROME.contentEquals(root.getPackageName())) return root;
        }
        return null;
    }

    private static String page(AccessibilityNodeInfo root) {
        if (root == null) return "";
        for (AccessibilityNodeInfo address : root.findAccessibilityNodeInfosByViewId(CHROME + ":id/url_bar")) {
            String raw = string(address.getText()).trim();
            if (raw.isEmpty() || address.isFocused()) continue;
            Uri uri = Uri.parse(raw.contains("://") ? raw : "https://" + raw);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) continue;
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equals("docs.google.com") && path.startsWith("/forms/") && !path.contains("/edit")) return uri.toString();
            for (String allowed : HOSTS) {
                if (host.equals(allowed) || host.endsWith("." + allowed)) return uri.toString();
            }
        }
        return "";
    }

    String currentPage() { return page(browser()); }

    Question question(java.util.Set<String> completed) {
        AccessibilityNodeInfo root = browser();
        String page = page(root);
        if (page.isEmpty()) return null;
        var candidates = new ArrayList<AccessibilityNodeInfo>();
        collect(root, candidates, 0);
        for (AccessibilityNodeInfo group : candidates) {
            var radio = new ArrayList<AccessibilityNodeInfo>();
            radios(group, radio, 0);
            if (radio.size() < 2 || radio.size() > 8) continue;
            var labels = new ArrayList<String>();
            boolean usable = true;
            for (AccessibilityNodeInfo option : radio) {
                String label = caption(option);
                if (label.isEmpty() || option.isChecked() || option.isSelected() || !option.isEnabled()) {
                    usable = false;
                    break;
                }
                labels.add(label);
            }
            if (!usable || new java.util.HashSet<>(labels).size() != labels.size()) continue;
            String text = questionText(group, labels);
            if (text.isEmpty()) continue;
            Question result = new Question(page, text, labels, radio);
            if (!completed.contains(result.identity())) return result;
        }
        return null;
    }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 45 || out.size() >= 1000) return;
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out, depth + 1);
        if (node.isVisibleToUser()) out.add(node);
    }

    private static void radios(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 6 || result.size() > 8 || !node.isVisibleToUser()) return;
        if ("android.widget.RadioButton".contentEquals(node.getClassName()) && node.isClickable()) {
            result.add(node);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) radios(node.getChild(i), result, depth + 1);
    }

    private static String caption(AccessibilityNodeInfo node) {
        String label = string(node.getText()).trim();
        return label.isEmpty() ? string(node.getContentDescription()).trim() : label;
    }

    private static String questionText(AccessibilityNodeInfo group, List<String> labels) {
        for (int level = 0; level < 3 && group != null; level++, group = group.getParent()) {
            var text = new ArrayList<String>();
            for (int i = 0; i < group.getChildCount(); i++) {
                AccessibilityNodeInfo child = group.getChild(i);
                if (child == null || !child.isVisibleToUser()) continue;
                String value = caption(child);
                if (!labels.contains(value) && !value.isEmpty() && value.length() <= 500) text.add(value);
            }
            if (!text.isEmpty()) return String.join("\n", text);
        }
        return "";
    }

    boolean answer(Question expected, int choice, java.util.Set<String> completed) {
        Question current = question(completed);
        if (current == null || !expected.identity().equals(current.identity()) ||
            choice < 0 || choice >= current.buttons().size()) return false;
        AccessibilityNodeInfo option = current.buttons().get(choice);
        return option.refresh() && option.isEnabled() && option.isVisibleToUser() && !option.isChecked()
            && option.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    boolean scroll(String expectedPage) {
        AccessibilityNodeInfo root = browser();
        if (expectedPage.isEmpty() || !expectedPage.equals(page(root))) return false;
        var nodes = new ArrayList<AccessibilityNodeInfo>();
        collect(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }

    private static String string(CharSequence text) { return text == null ? "" : text.toString(); }
}
