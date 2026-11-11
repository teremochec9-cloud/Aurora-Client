package dev.aurora.client.util;

import java.util.ArrayDeque;

/**
 * Трекер кликов мыши: нажатия для CPS и состояние кнопок для Keystrokes.
 * Обновляется из миксина MouseHandler.
 */
public final class ClickTracker {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();
    public static volatile boolean leftDown;
    public static volatile boolean rightDown;

    private ClickTracker() {
    }

    public static void onPress(int button) {
        long now = System.currentTimeMillis();
        if (button == 0) {
            LEFT.addLast(now);
            leftDown = true;
        } else if (button == 1) {
            RIGHT.addLast(now);
            rightDown = true;
        }
    }

    public static void onRelease(int button) {
        if (button == 0) {
            leftDown = false;
        } else if (button == 1) {
            rightDown = false;
        }
    }

    private static int cps(ArrayDeque<Long> deque) {
        long now = System.currentTimeMillis();
        while (!deque.isEmpty() && now - deque.peekFirst() > 1000) {
            deque.pollFirst();
        }
        return deque.size();
    }

    public static int leftCps() {
        return cps(LEFT);
    }

    public static int rightCps() {
        return cps(RIGHT);
    }
}
