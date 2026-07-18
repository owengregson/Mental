package me.vexmc.mental.v5.gui;

import org.jetbrains.annotations.NotNull;

/**
 * The one placement vocabulary — pure slot math shared by every screen. Replaces
 * the retired {@code Menu.placeCentered} internals and the old
 * {@code FamilyMenu.spreadColumns}, so the screens never hand-count slot indices
 * again. Fully unit-pinned by {@code LayoutTest}.
 */
final class Layout {

    private static final int WIDTH = 9;

    private Layout() {}

    /**
     * Contiguous centred slots across the full 9-wide row whose first slot is
     * {@code rowBase} (the home rows). Mirrors the retired {@code placeCentered}
     * math: {@code start = rowBase + max(0, (9 - count) / 2)}, with any overflow
     * past the row's last column dropped.
     */
    static int[] centeredRow(int rowBase, int count) {
        int start = rowBase + Math.max(0, (WIDTH - count) / 2);
        int placed = 0;
        for (int i = 0; i < count && start + i <= rowBase + (WIDTH - 1); i++) {
            placed++;
        }
        int[] slots = new int[placed];
        for (int i = 0; i < placed; i++) {
            slots[i] = start + i;
        }
        return slots;
    }

    /**
     * Content slots WITHIN the chrome frame (cols 1..7): gapped every-other
     * column while {@code 2*count - 1 <= 7} (count &le; 4), contiguous centred
     * for count 5..7. count &gt; 7 is a caller bug — pages must paginate or group
     * instead of overflowing the frame.
     */
    static int[] contentRow(int rowBase, int count) {
        if (count > 7) {
            throw new IllegalArgumentException(
                    "content row holds at most 7 tiles inside the frame; got " + count);
        }
        boolean gapped = 2 * count - 1 <= 7;
        int first;
        int stride;
        if (gapped) {
            first = rowBase + 1 + (7 - (2 * count - 1)) / 2;
            stride = 2;
        } else {
            first = rowBase + 1 + (7 - count) / 2;
            stride = 1;
        }
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = first + i * stride;
        }
        return slots;
    }

    /** The gallery grid: rows 1-3, cols 1..7 → {10..16, 19..25, 28..34}. */
    static int[] galleryGrid() {
        int[] slots = new int[21];
        int i = 0;
        for (int row = 1; row <= 3; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[i++] = row * WIDTH + col;
            }
        }
        return slots;
    }

    /** Pure pagination window over a list; {@code page} is clamped into range. */
    record Page(int pageCount, int page, int fromIndex, int toIndex,
                boolean hasPrev, boolean hasNext) {}

    /**
     * The pagination window for a list of {@code itemCount} items shown
     * {@code pageSize} per page, at the (clamped) {@code requestedPage}.
     */
    static @NotNull Page page(int itemCount, int pageSize, int requestedPage) {
        int pageCount = Math.max(1, (int) Math.ceil((double) itemCount / pageSize));
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int fromIndex = page * pageSize;
        int toIndex = Math.min(itemCount, fromIndex + pageSize);
        boolean hasPrev = page > 0;
        boolean hasNext = page < pageCount - 1;
        return new Page(pageCount, page, fromIndex, toIndex, hasPrev, hasNext);
    }
}
