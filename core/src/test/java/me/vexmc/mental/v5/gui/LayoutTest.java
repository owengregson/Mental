package me.vexmc.mental.v5.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the shared placement math (§2.5). Every expectation is
 * derived by hand from the spec, never by running the code under test.
 */
class LayoutTest {

    @Test
    void centeredRowMatchesTheRetiredPlaceCenteredMath() {
        // base 9, counts 1..9 — the retired placeCentered values.
        assertArrayEquals(new int[] {13}, Layout.centeredRow(9, 1));
        assertArrayEquals(new int[] {12, 13}, Layout.centeredRow(9, 2));
        assertArrayEquals(new int[] {12, 13, 14}, Layout.centeredRow(9, 3));
        assertArrayEquals(new int[] {11, 12, 13, 14}, Layout.centeredRow(9, 4));
        assertArrayEquals(new int[] {11, 12, 13, 14, 15}, Layout.centeredRow(9, 5));
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15}, Layout.centeredRow(9, 6));
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16}, Layout.centeredRow(9, 7));
        assertArrayEquals(new int[] {9, 10, 11, 12, 13, 14, 15, 16}, Layout.centeredRow(9, 8));
        assertArrayEquals(new int[] {9, 10, 11, 12, 13, 14, 15, 16, 17}, Layout.centeredRow(9, 9));
    }

    @Test
    void contentRowGapsUpToFourThenPacks() {
        // base 9 — the §2.5 table verbatim.
        assertArrayEquals(new int[] {13}, Layout.contentRow(9, 1));
        assertArrayEquals(new int[] {12, 14}, Layout.contentRow(9, 2));
        assertArrayEquals(new int[] {11, 13, 15}, Layout.contentRow(9, 3));
        assertArrayEquals(new int[] {10, 12, 14, 16}, Layout.contentRow(9, 4));
        assertArrayEquals(new int[] {11, 12, 13, 14, 15}, Layout.contentRow(9, 5));
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16}, Layout.contentRow(9, 7));
    }

    @Test
    void contentRowRejectsMoreThanSeven() {
        assertThrows(IllegalArgumentException.class, () -> Layout.contentRow(9, 8));
    }

    @Test
    void galleryGridIsTheThreeSevenWideRows() {
        assertArrayEquals(new int[] {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
        }, Layout.galleryGrid());
    }

    @Test
    void paginationWindowsClampAndCount() {
        // itemCount 0, 1, 21, 22, 43 × pageSize 21 — pageCount/from/to/hasPrev/hasNext.
        Layout.Page empty = Layout.page(0, 21, 0);
        assertEquals(1, empty.pageCount());
        assertEquals(0, empty.page());
        assertEquals(0, empty.fromIndex());
        assertEquals(0, empty.toIndex());
        assertFalse(empty.hasPrev());
        assertFalse(empty.hasNext());

        // A negative request clamps to the first page.
        Layout.Page emptyClamped = Layout.page(0, 21, 5);
        assertEquals(0, emptyClamped.page());

        Layout.Page one = Layout.page(1, 21, 0);
        assertEquals(1, one.pageCount());
        assertEquals(0, one.fromIndex());
        assertEquals(1, one.toIndex());
        assertFalse(one.hasNext());

        Layout.Page exact = Layout.page(21, 21, 0);
        assertEquals(1, exact.pageCount());
        assertEquals(0, exact.fromIndex());
        assertEquals(21, exact.toIndex());
        assertFalse(exact.hasNext());

        Layout.Page firstOfTwo = Layout.page(22, 21, 0);
        assertEquals(2, firstOfTwo.pageCount());
        assertEquals(0, firstOfTwo.fromIndex());
        assertEquals(21, firstOfTwo.toIndex());
        assertFalse(firstOfTwo.hasPrev());
        assertTrue(firstOfTwo.hasNext());

        Layout.Page secondOfTwo = Layout.page(22, 21, 1);
        assertEquals(1, secondOfTwo.page());
        assertEquals(21, secondOfTwo.fromIndex());
        assertEquals(22, secondOfTwo.toIndex());
        assertTrue(secondOfTwo.hasPrev());
        assertFalse(secondOfTwo.hasNext());

        // Over-range request clamps to the last page.
        Layout.Page clampedHigh = Layout.page(22, 21, 9);
        assertEquals(1, clampedHigh.page());

        Layout.Page lastOfThree = Layout.page(43, 21, 2);
        assertEquals(3, lastOfThree.pageCount());
        assertEquals(42, lastOfThree.fromIndex());
        assertEquals(43, lastOfThree.toIndex());
        assertTrue(lastOfThree.hasPrev());
        assertFalse(lastOfThree.hasNext());
    }
}
