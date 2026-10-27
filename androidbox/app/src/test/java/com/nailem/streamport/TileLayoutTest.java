package com.nailem.streamport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TileLayoutTest {

    private static final float PHONE = 9f / 19.5f;
    private static final float WIDE = 16f / 9f;

    private static double area(int[][] r) {
        double a = 0;
        for (int[] x : r) a += (double) x[2] * x[3];
        return a;
    }

    @Test
    public void onePictureFillsTheScreen() {
        int[][] r = TileLayout.fill(new float[]{WIDE}, 1920, 1080, 0);
        assertEquals(1920, r[0][2]);
        assertEquals(1080, r[0][3]);
    }

    @Test
    public void fourLaptopsFillTheScreen() {
        int[][] r = TileLayout.fill(new float[]{WIDE, WIDE, WIDE, WIDE}, 1920, 1080, 0);
        assertTrue(area(r) > 0.99 * 1920 * 1080);
        for (int[] x : r) assertEquals(960, x[2], 1);
    }

    @Test
    public void threePhonesMakeOneRow() {
        int[][] r = TileLayout.fill(new float[]{PHONE, PHONE, PHONE}, 1920, 1080, 24);
        assertEquals(r[0][1], r[2][1]);
        assertEquals(1080, r[0][3], 1);
    }

    /** The case equal cells handled badly: a portrait phone among laptops. */
    @Test
    public void phoneAmongLaptopsUsesMoreOfTheScreen() {
        float[] a = {WIDE, WIDE, WIDE, PHONE};
        int[][] r = TileLayout.fill(a, 1920, 1080, 24);
        for (int[] x : r) System.out.println("rect " + java.util.Arrays.toString(x));
        System.out.println("covered " + area(r) / (1920 * 1080));
        // Better than the old 2x2 grid of equal cells (about 0.62 of the screen for this mix).
        assertTrue(area(r) > 0.7 * 1920 * 1080);
    }

    @Test
    public void nobodyBecomesAThumbnail() {
        float[] a = {WIDE, PHONE, 4f, 0.3f};
        int[][] r = TileLayout.fill(a, 1920, 1080, 24);
        double max = 0;
        double min = Double.MAX_VALUE;
        for (int[] x : r) {
            max = Math.max(max, (double) x[2] * x[3]);
            min = Math.min(min, (double) x[2] * x[3]);
        }
        assertTrue(min >= 0.24 * max);
    }

    @Test
    public void tilesStayOnScreenApartAndAtTheirAspect() {
        float[][] cases = {{WIDE, PHONE}, {PHONE, WIDE, WIDE}, {WIDE, WIDE, PHONE, PHONE}, {1f, 4f, 0.25f},
                {16f / 10f, PHONE, WIDE, 4f / 3f}};
        for (float[] c : cases) {
            int[][] r = TileLayout.fill(c, 1920, 1080, 24);
            for (int i = 0; i < r.length; i++) {
                assertTrue(r[i][0] >= 0 && r[i][1] >= 0);
                assertTrue(r[i][0] + r[i][2] <= 1920 + 1 && r[i][1] + r[i][3] <= 1080 + 1);
                assertEquals(c[i], (float) r[i][2] / r[i][3], c[i] * 0.03f);
                for (int j = i + 1; j < r.length; j++) {
                    boolean apart = r[i][0] + r[i][2] <= r[j][0] + 1 || r[j][0] + r[j][2] <= r[i][0] + 1
                            || r[i][1] + r[i][3] <= r[j][1] + 1 || r[j][1] + r[j][3] <= r[i][1] + 1;
                    assertTrue(apart);
                }
            }
        }
    }

    private static int biggest(int[][] r) {
        int b = 0;
        for (int i = 1; i < r.length; i++) if ((double) r[i][2] * r[i][3] > (double) r[b][2] * r[b][3]) b = i;
        return b;
    }

    @Test
    public void primaryIsBiggestWhicheverItIs() {
        float[][] cases = {{WIDE, WIDE}, {WIDE, PHONE, WIDE}, {WIDE, WIDE, WIDE, PHONE}, {PHONE, PHONE, WIDE, 4f / 3f}};
        for (float[] c : cases) {
            for (int p = 0; p < c.length; p++) {
                int[][] r = TileLayout.dynamic(c, p, 1920, 1080, 24);
                double pa = (double) r[p][2] * r[p][3];
                for (int i = 0; i < r.length; i++) {
                    double a = (double) r[i][2] * r[i][3];
                    assertTrue("case " + java.util.Arrays.toString(c) + " primary " + p, a <= pa * 1.001);
                    assertTrue("tile " + i + " too small", a >= pa / 9 * 0.98);
                }
            }
        }
    }

    @Test
    public void twoLaptopsPrimaryIsClearlyBigger() {
        int[][] r = TileLayout.dynamic(new float[]{WIDE, WIDE}, 1, 1920, 1080, 24);
        for (int[] x : r) System.out.println("dyn2 " + java.util.Arrays.toString(x));
        assertEquals(1, biggest(r));
        assertTrue((double) r[1][2] * r[1][3] > 2.0 * r[0][2] * r[0][3]);
    }

    @Test
    public void dynamicLayoutsStayOnScreenAndApart() {
        float[][] cases = {{WIDE, PHONE}, {PHONE, WIDE, WIDE}, {WIDE, WIDE, PHONE, PHONE}, {1f, 4f, 0.25f},
                {16f / 10f, PHONE, WIDE, 4f / 3f}};
        for (float[] c : cases) {
            for (int p = 0; p < c.length; p++) {
                int[][] r = TileLayout.dynamic(c, p, 1920, 1080, 24);
                for (int i = 0; i < r.length; i++) {
                    assertTrue(r[i][0] >= -1 && r[i][1] >= -1);
                    assertTrue(r[i][0] + r[i][2] <= 1921 && r[i][1] + r[i][3] <= 1081);
                    for (int j = i + 1; j < r.length; j++) {
                        boolean apart = r[i][0] + r[i][2] <= r[j][0] + 1 || r[j][0] + r[j][2] <= r[i][0] + 1
                                || r[i][1] + r[i][3] <= r[j][1] + 1 || r[j][1] + r[j][3] <= r[i][1] + 1;
                        assertTrue("overlap " + java.util.Arrays.toString(c) + " p=" + p, apart);
                    }
                }
            }
        }
    }

    @Test
    public void gridGivesEqualCells() {
        int[][] r = TileLayout.grid(new float[]{WIDE, WIDE, WIDE, WIDE}, 1920, 1080, 24);
        for (int[] x : r) {
            assertEquals(r[0][2], x[2]);
            assertEquals(r[0][3], x[3]);
        }
    }
}
