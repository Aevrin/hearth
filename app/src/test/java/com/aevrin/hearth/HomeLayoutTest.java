/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * The layout engine: placing, sliding, moving, widgets, folders, pages, the
 * subgrid, and never losing anything when the grid shrinks. Runs in CI on every
 * push, so a mistake here can't quietly scramble someone's home screen.
 */
public class HomeLayoutTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String H = HomeLayout.HOME, D = HomeLayout.DOCK;

    private HomeLayout load(int cols, int rows, int pages, int dockSlots, int sub) {
        return HomeLayout.load(dir(), cols, rows, pages, dockSlots, 1, sub);
    }

    private File dir() {
        return tmp.getRoot();
    }

    private static HomeLayout.Item find(HomeLayout l, String key) {
        for (HomeLayout.Item it : l.items()) if (it.key.equals(key)) return it;
        return null;
    }

    private static void assertAt(HomeLayout l, String key, String container, int page, int x, int y) {
        HomeLayout.Item it = find(l, key);
        assertNotNull(key + " missing", it);
        assertEquals(key + " container", container, it.container);
        assertEquals(key + " page", page, it.page);
        assertEquals(key + " x", x, it.x);
        assertEquals(key + " y", y, it.y);
    }

    /** No two placed items share a cell. */
    private static void assertNoOverlaps(HomeLayout l) {
        List<HomeLayout.Item> items = l.items();
        for (int i = 0; i < items.size(); i++) {
            HomeLayout.Item a = items.get(i);
            if (!l.fitsItem(a, a.container, a.page, a.x, a.y)) continue; // waiting for room
            for (int j = i + 1; j < items.size(); j++) {
                HomeLayout.Item b = items.get(j);
                if (!l.fitsItem(b, b.container, b.page, b.x, b.y)) continue;
                assertFalse(a.key + " overlaps " + b.key, a.overlaps(b.container, b.page, b.x, b.y, b.spanX, b.spanY));
            }
        }
    }

    /* ---------- placing and sliding ---------- */

    @Test
    public void addFillsInReadingOrder() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        assertTrue(l.add("a", H, 0));
        assertTrue(l.add("b", H, 0));
        assertTrue(l.add("c", H, 0));
        assertAt(l, "a", H, 0, 0, 0);
        assertAt(l, "b", H, 0, 1, 0);
        assertAt(l, "c", H, 0, 2, 0);
    }

    @Test
    public void addReportsFullGrid() {
        HomeLayout l = load(2, 2, 1, 5, 1);
        for (String k : Arrays.asList("a", "b", "c", "d")) assertTrue(l.add(k, H, 0));
        assertFalse(l.add("e", H, 0));
        assertEquals(4, l.items().size());
    }

    @Test
    public void insertSlidesTheRestAlong() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        l.add("c", H, 0);
        assertTrue(l.insertAt(null, "x", H, 0, 1, 0));
        assertAt(l, "a", H, 0, 0, 0);
        assertAt(l, "x", H, 0, 1, 0);
        assertAt(l, "b", H, 0, 2, 0);
        assertAt(l, "c", H, 0, 3, 0);
        assertNoOverlaps(l);
    }

    @Test
    public void insertOnFullPageChangesNothing() {
        HomeLayout l = load(2, 2, 1, 5, 1);
        for (String k : Arrays.asList("a", "b", "c", "d")) l.add(k, H, 0);
        assertFalse(l.insertAt(null, "x", H, 0, 0, 0));
        assertNull(find(l, "x"));
        assertAt(l, "a", H, 0, 0, 0);
        assertAt(l, "d", H, 0, 1, 1);
    }

    @Test
    public void insertPreviewMatchesTheDrop() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        java.util.Map<HomeLayout.Item, int[]> plan = l.insertPlan(null, H, 0, 0, 0);
        assertNotNull(plan);
        int[] aTo = plan.get(find(l, "a"));
        l.insertAt(null, "x", H, 0, 0, 0);
        assertAt(l, "a", H, 0, aTo[0], aTo[1]);
    }

    @Test
    public void iconsSlideAroundWidgets() {
        HomeLayout l = load(4, 2, 1, 5, 1);
        HomeLayout.Item w = l.addWidget(7, 0, 2, 1); // covers (0,0) and (1,0)
        assertNotNull(w);
        l.add("a", H, 0); // (2,0)
        l.add("b", H, 0); // (3,0)
        assertAt(l, "a", H, 0, 2, 0);
        assertTrue(l.insertAt(null, "x", H, 0, 2, 0));
        assertAt(l, "x", H, 0, 2, 0);
        assertAt(l, "a", H, 0, 3, 0);
        assertAt(l, "b", H, 0, 0, 1); // skipped the widget's cells
        assertEquals(0, w.x);
        assertEquals(0, w.y);
        assertNoOverlaps(l);
    }

    @Test
    public void cantInsertOntoAWidget() {
        HomeLayout l = load(4, 2, 1, 5, 1);
        l.addWidget(7, 0, 2, 1);
        assertFalse(l.insertAt(null, "x", H, 0, 1, 0));
        assertNull(find(l, "x"));
    }

    /* ---------- moving ---------- */

    @Test
    public void moveOntoAnIconSwaps() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        l.move(find(l, "a"), H, 0, 1, 0);
        assertAt(l, "a", H, 0, 1, 0);
        assertAt(l, "b", H, 0, 0, 0);
    }

    @Test
    public void moveBetweenHomeAndDock() {
        HomeLayout l = load(4, 4, 1, 4, 1);
        l.add("a", H, 0);
        l.add("d", D, 0);
        l.move(find(l, "a"), D, 0, 0, 0); // onto the dock icon: swap
        assertAt(l, "a", D, 0, 0, 0);
        assertAt(l, "d", H, 0, 0, 0);
    }

    @Test
    public void widgetsOnlyMoveIntoFreeSpace() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        HomeLayout.Item w = l.addWidget(1, 0, 2, 2); // (0,0)
        l.add("a", H, 0); // (2,0)
        l.move(w, H, 0, 1, 0); // would cover "a"
        assertEquals(0, w.x);
        l.move(w, H, 0, 0, 2);
        assertEquals(0, w.x);
        assertEquals(2, w.y);
        l.move(w, D, 0, 0, 0); // two rows tall: not into the dock
        assertEquals(H, w.container);
        assertNoOverlaps(l);
    }

    @Test
    public void resizeRespectsNeighbours() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        HomeLayout.Item w = l.addWidget(1, 0, 2, 2);
        l.add("a", H, 0); // (2,0)
        assertFalse(l.canResize(w, 0, 0, 3, 2));
        assertFalse(l.resizeItemTo(w, 0, 0, 3, 2));
        assertEquals(2, w.spanX);
        assertTrue(l.resizeItemTo(w, 0, 0, 2, 3));
        assertEquals(3, w.spanY);
        assertFalse(l.canResize(w, 0, 0, 2, 5)); // off the grid
    }

    /* ---------- widgets in the dock ---------- */

    @Test
    public void oneRowWidgetsFitInTheDock() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        HomeLayout.Item w = l.addWidget(3, 0, 2, 1);
        l.move(w, D, 0, 1, 0);
        assertEquals(D, w.container);
        assertEquals(1, w.x);
        assertEquals(2, w.spanX);
        assertEquals(1, w.spanY);
        l.move(w, H, 0, 0, 3); // and back
        assertEquals(H, w.container);
        assertEquals(2, w.spanX);
        assertEquals(1, w.spanY);
        assertNoOverlaps(l);
    }

    @Test
    public void tallWidgetsStayOutOfTheDock() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        HomeLayout.Item w = l.addWidget(3, 0, 2, 2);
        l.move(w, D, 0, 0, 0);
        assertEquals(H, w.container);
        assertEquals(2, w.spanY);
    }

    @Test
    public void dockWidgetsDontOverlapIcons() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("d", D, 0); // slot 0
        HomeLayout.Item w = l.addWidget(3, 0, 2, 1);
        l.move(w, D, 0, 0, 0); // would cover "d"
        assertEquals(H, w.container);
        l.move(w, D, 0, 1, 0);
        assertEquals(D, w.container);
        l.move(find(l, "d"), D, 0, 1, 0); // an icon can't swap onto a widget
        assertAt(l, "d", D, 0, 0, 0);
        assertNoOverlaps(l);
    }

    @Test
    public void dockWidgetsSurviveReloadAndShrinkWithTheDock() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        HomeLayout.Item w = l.addWidget(3, 0, 3, 1);
        l.move(w, D, 0, 0, 0);
        HomeLayout again = load(4, 4, 1, 5, 1);
        HomeLayout.Item w2 = find(again, HomeLayout.WIDGET_PREFIX + 3);
        assertEquals(D, w2.container);
        assertEquals(3, w2.spanX);
        HomeLayout fewer = load(4, 4, 1, 2, 1); // two slots now
        HomeLayout.Item w3 = find(fewer, HomeLayout.WIDGET_PREFIX + 3);
        assertEquals(D, w3.container);
        assertEquals(2, w3.spanX);
        assertNoOverlaps(fewer);
    }

    @Test
    public void subgridWidgetsTranslateToDockSlots() {
        HomeLayout l = load(4, 4, 1, 5, 2);
        HomeLayout.Item w = l.addWidget(4, 0, 4, 2); // two cells wide, one row (in half cells)
        l.move(w, D, 0, 0, 0);
        assertEquals(D, w.container);
        assertEquals(2, w.spanX);
        assertEquals(1, w.spanY);
        l.move(w, H, 0, 0, 4);
        assertEquals(4, w.spanX);
        assertEquals(2, w.spanY);
        HomeLayout.Item tall = l.addWidget(5, 0, 2, 4); // two rows: no
        l.move(tall, D, 0, 3, 0);
        assertEquals(H, tall.container);
    }

    /* ---------- folders ---------- */

    @Test
    public void folderLifecycle() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        l.add("c", H, 0);
        HomeLayout.Item a = find(l, "a");
        HomeLayout.Folder f = l.createFolder(a, "b", find(l, "b"), "Games");
        assertNull("b's cell is freed", find(l, "b"));
        assertEquals(Arrays.asList("a", "b"), f.apps);
        assertTrue(HomeLayout.isFolderKey(a.key));
        l.addToFolder(f, "c", find(l, "c"));
        assertEquals(3, f.apps.size());
        l.setFolderOrder(f, Arrays.asList("c", "a", "b"));
        assertEquals(Arrays.asList("c", "a", "b"), f.apps);
        l.removeFromFolder(f, "c");
        l.removeFromFolder(f, "b");
        assertEquals("a folder of one turns back into the app", "a", a.key);
    }

    @Test
    public void moveOutOfFolderPlacesOnHome() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        l.add("c", H, 0);
        HomeLayout.Folder f = l.createFolder(find(l, "a"), "b", find(l, "b"), "");
        l.addToFolder(f, "c", find(l, "c"));
        assertTrue(l.moveOutOfFolder(f, "c"));
        assertNotNull(find(l, "c"));
        assertFalse(f.apps.contains("c"));
        assertTrue(l.containsElsewhere(f, "c"));
        assertNoOverlaps(l);
    }

    /* ---------- pages ---------- */

    @Test
    public void deletingAPageRehomesItsIcons() {
        HomeLayout l = load(3, 3, 2, 5, 1);
        l.add("a", H, 0);
        l.add("p1", H, 1);
        l.deletePage(1);
        assertEquals(1, l.pages());
        assertAt(l, "p1", H, 0, 1, 0);
        assertNoOverlaps(l);
    }

    @Test
    public void swapPages() {
        HomeLayout l = load(3, 3, 2, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 1);
        l.swapPages(0, 1);
        assertEquals(1, find(l, "a").page);
        assertEquals(0, find(l, "b").page);
    }

    /* ---------- shrinking never loses anything ---------- */

    @Test
    public void shrinkingTheGridKeepsEveryIcon() {
        HomeLayout l = load(5, 5, 1, 5, 1);
        for (int i = 0; i < 12; i++) l.add("app" + i, H, 0);
        HomeLayout small = load(3, 3, 2, 5, 1); // 18 cells over two pages
        assertEquals(12, small.items().size());
        for (HomeLayout.Item it : small.items()) {
            assertTrue(it.key + " fits", small.fitsItem(it, it.container, it.page, it.x, it.y));
        }
        assertNoOverlaps(small);
    }

    @Test
    public void fewerDockSlotsMovesTheOverflowHome() {
        HomeLayout l = load(4, 4, 1, 4, 1);
        for (String k : Arrays.asList("d1", "d2", "d3", "d4")) l.add(k, D, 0);
        HomeLayout fewer = load(4, 4, 1, 3, 1);
        assertEquals(4, fewer.items().size());
        int home = 0;
        for (HomeLayout.Item it : fewer.items()) if (H.equals(it.container)) home++;
        assertEquals(1, home);
        assertNoOverlaps(fewer);
    }

    /* ---------- saving ---------- */

    @Test
    public void everythingSurvivesASaveAndReload() {
        HomeLayout l = load(4, 4, 2, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        l.add("d", D, 0);
        HomeLayout.Folder f = l.createFolder(find(l, "a"), "b", find(l, "b"), "Work");
        l.setFolderIcon(f, "text:W");
        l.addWidget(9, 1, 2, 2);
        HomeLayout again = load(4, 4, 2, 5, 1);
        assertEquals(l.items().size(), again.items().size());
        HomeLayout.Item folderItem = null;
        for (HomeLayout.Item it : again.items()) if (HomeLayout.isFolderKey(it.key)) folderItem = it;
        assertNotNull(folderItem);
        HomeLayout.Folder f2 = again.folder(folderItem);
        assertEquals("Work", f2.name);
        assertEquals("text:W", f2.icon);
        assertEquals(Arrays.asList("a", "b"), f2.apps);
        HomeLayout.Item w = find(again, HomeLayout.WIDGET_PREFIX + 9);
        assertNotNull(w);
        assertEquals(2, w.spanX);
        assertEquals(1, w.page);
        assertAt(again, "d", D, 0, 0, 0);
    }

    /* ---------- subgrid ---------- */

    @Test
    public void subgridDoublesAndRestoresTheLayout() {
        HomeLayout l = load(4, 4, 1, 5, 1);
        l.add("a", H, 0);
        l.add("b", H, 0);
        l.addWidget(5, 0, 2, 2); // first free 2x2: (2,0)
        l.add("d", D, 0);

        HomeLayout fine = load(4, 4, 1, 5, 2);
        assertEquals(8, fine.columns());
        HomeLayout.Item a = find(fine, "a"), b = find(fine, "b"), w = find(fine, HomeLayout.WIDGET_PREFIX + 5);
        assertEquals(0, a.x);
        assertEquals(2, a.spanX);
        assertEquals(2, b.x);
        assertEquals(4, w.x);
        assertEquals(4, w.spanX);
        assertEquals("dock icons stay 1x1", 1, find(fine, "d").spanX);
        assertNoOverlaps(fine);

        HomeLayout back = load(4, 4, 1, 5, 1);
        assertAt(back, "a", H, 0, 0, 0);
        assertAt(back, "b", H, 0, 1, 0);
        HomeLayout.Item w2 = find(back, HomeLayout.WIDGET_PREFIX + 5);
        assertEquals(2, w2.x);
        assertEquals(2, w2.spanX);
        assertEquals(1, find(back, "a").spanX);
    }

    @Test
    public void onTheSubgridIconsTakeTwoByTwoAndDontSlide() {
        HomeLayout l = load(4, 4, 1, 5, 2);
        assertTrue(l.add("a", H, 0));
        HomeLayout.Item a = find(l, "a");
        assertEquals(2, a.spanX);
        assertEquals(2, a.spanY);
        assertFalse(l.insertAt(null, "x", H, 0, 0, 0)); // no sliding on the subgrid
        l.move(a, H, 0, 1, 1); // a half-cell step
        assertEquals(1, a.x);
        assertEquals(1, a.y);
    }

    @Test
    public void iconsFromTheDockGrowOnTheSubgrid() {
        HomeLayout l = load(4, 4, 1, 5, 2);
        l.add("d", D, 0);
        HomeLayout.Item d = find(l, "d");
        assertEquals(1, d.spanX);
        l.move(d, H, 0, 2, 2);
        assertEquals(H, d.container);
        assertEquals(2, d.spanX);
        l.move(d, D, 0, 1, 0);
        assertEquals(1, d.spanX);
    }
}
