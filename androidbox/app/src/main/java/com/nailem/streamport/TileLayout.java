package com.nailem.streamport;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each picture goes. Every picture keeps its own aspect ratio and is never cropped or stretched.
 * <ul>
 * <li>{@link #grid}: equal cells; the row count that shows the most picture wins.</li>
 * <li>{@link #dynamic}: the primary picture is the biggest, and everything together covers as much of the screen
 * as it can.</li>
 * <li>{@link #fill}: the most total picture, no primary (also used for one picture full screen).</li>
 * </ul>
 * Candidates for {@link #fill} and {@link #dynamic} are "guillotine" layouts: the screen split into a row or a
 * column, each part split again, and so on. Within a row the pictures share a height and within a column a width,
 * so neighbours meet edge to edge; only the margin around the whole arrangement is left over. For up to four
 * pictures every order and every split tree is tried (under a thousand). Two pictures of the same shape can't
 * differ in size that way, so {@link #dynamic} also tries "presenter" layouts: the primary on one side and the
 * others in a strip along the right or bottom edge, at every strip width from 12% to 50%.
 */
final class TileLayout {

    /** In {@link #fill} no picture may be smaller than this share of the biggest (nobody becomes a thumbnail). */
    private static final double FILL_MIN_SHARE = 0.25;
    /** In {@link #dynamic} the others may shrink further next to the primary, but not below this share of it. */
    private static final double PRIMARY_MIN_SHARE = 1.0 / 9;
    /** The primary's area counts this many times in {@link #dynamic}, so being bigger beats a tidy split. */
    private static final double PRIMARY_WEIGHT = 2;
    private static final double KEEP_ORDER_SLACK = 0.98;

    private TileLayout() {
    }

    interface Scorer {
        /** Higher is better; NaN rejects the layout. */
        double score(int[][] rects);
    }

    // ---- public layouts ----

    /** Returns {left, top, width, height} per picture: most total picture, fairly shared. */
    static int[][] fill(float[] aspects, int width, int height, int gap) {
        double[] a = clean(aspects);
        Scorer fair = rects -> {
            double max = 0;
            double min = Double.MAX_VALUE;
            for (int[] r : rects) {
                max = Math.max(max, area(r));
                min = Math.min(min, area(r));
            }
            // A fair layout always beats an unfair one; among equals, more picture wins.
            return total(rects) + (min >= FILL_MIN_SHARE * max ? 1e12 : 0);
        };
        int[][] best = search(a, width, height, gap, fair, true);
        return best != null ? best : grid(aspects, width, height, gap);
    }

    /** The primary (index into aspects) biggest; the rest fit around it. */
    static int[][] dynamic(float[] aspects, int primary, int width, int height, int gap) {
        int n = aspects.length;
        if (n <= 1 || primary < 0 || primary >= n) return fill(aspects, width, height, gap);
        double[] a = clean(aspects);
        Scorer withPrimary = rects -> {
            double p = area(rects[primary]);
            for (int i = 0; i < rects.length; i++) {
                if (i == primary) continue;
                double o = area(rects[i]);
                if (o > p * 1.001 || o < p * PRIMARY_MIN_SHARE) return Double.NaN;
            }
            return total(rects) + (PRIMARY_WEIGHT - 1) * p;
        };
        int[][] best = search(a, width, height, gap, withPrimary, false);
        double bestScore = best != null ? withPrimary.score(best) : Double.NEGATIVE_INFINITY;

        double[] others = new double[n - 1];
        int[] map = new int[n - 1];
        for (int i = 0, k = 0; i < n; i++) {
            if (i == primary) continue;
            others[k] = a[i];
            map[k++] = i;
        }
        for (int f = 12; f <= 50; f += 2) {
            for (boolean right : new boolean[]{true, false}) {
                int[][] rects = presenter(a[primary], others, map, primary, right, f / 100.0, width, height, gap);
                if (rects == null) continue;
                double s = withPrimary.score(rects);
                if (!Double.isNaN(s) && s > bestScore) {
                    bestScore = s;
                    best = rects;
                }
            }
        }
        return best != null ? center(best, width, height) : fill(aspects, width, height, gap);
    }

    /** Equal cells; the row count that shows the most picture wins. A short last row is centred. */
    static int[][] grid(float[] aspects, int width, int height, int gap) {
        int n = aspects.length;
        double[] a = clean(aspects);
        int[][] best = new int[n][];
        double bestArea = -1;
        for (int rows = 1; rows <= n; rows++) {
            int cols = (n + rows - 1) / rows;
            if ((rows - 1) * cols >= n) continue;
            int cellW = (width - gap * (cols + 1)) / cols;
            int cellH = (height - gap * (rows + 1)) / rows;
            if (cellW <= 0 || cellH <= 0) continue;
            int[][] rects = new int[n][];
            for (int i = 0; i < n; i++) {
                int row = i / cols;
                int inRow = Math.min(cols, n - row * cols);
                int rowStart = (width - (inRow * cellW + (inRow - 1) * gap)) / 2;
                int cellX = rowStart + (i % cols) * (cellW + gap);
                int cellY = gap + row * (cellH + gap) + ((height - gap * (rows + 1)) - rows * cellH) / 2;
                rects[i] = fit(a[i], cellX, cellY, cellW, cellH);
            }
            double area = total(rects);
            if (area > bestArea * 1.0001) {
                bestArea = area;
                best = rects;
            }
        }
        return best;
    }

    // ---- presenter: primary plus a strip of the others ----

    private static int[][] presenter(double primaryAspect, double[] others, int[] map, int primary, boolean right,
                                     double fraction, int width, int height, int gap) {
        int n = others.length + 1;
        int[][] rects = new int[n][];
        int stripW;
        int stripH;
        int[] mainRegion;
        if (right) {
            stripW = (int) Math.round(width * fraction);
            stripH = height;
            mainRegion = new int[]{0, 0, width - stripW - gap, height};
        } else {
            stripW = width;
            stripH = (int) Math.round(height * fraction);
            mainRegion = new int[]{0, 0, width, height - stripH - gap};
        }
        if (mainRegion[2] <= 0 || mainRegion[3] <= 0) return null;
        int[] p = fit(primaryAspect, mainRegion[0], mainRegion[1], mainRegion[2], mainRegion[3]);
        // Against the far edge, so all the room left over goes to the strip.
        if (right) p[0] = 0;
        else p[1] = 0;
        // Pull the strip up against the primary so the pair reads as one block once centred.
        int stripX = right ? p[0] + p[2] + gap : 0;
        int stripY = right ? 0 : p[1] + p[3] + gap;
        int availW = right ? width - stripX : stripW;
        int availH = right ? stripH : height - stripY;
        Scorer most = TileLayout::total;
        int[][] strip = search(others, availW, availH, gap, most, false);
        if (strip == null) return null;
        // The strip's own layout is centred in its area; pack it against the primary instead, so the two read
        // as one block (dynamic() centres the block on screen).
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (int[] r : strip) {
            minX = Math.min(minX, r[0]);
            minY = Math.min(minY, r[1]);
        }
        int dx = right ? -minX : 0;
        int dy = right ? 0 : -minY;
        rects[primary] = p;
        for (int k = 0; k < strip.length; k++) {
            int[] r = strip[k];
            rects[map[k]] = new int[]{r[0] + stripX + dx, r[1] + stripY + dy, r[2], r[3]};
        }
        return rects;
    }

    // ---- guillotine search ----

    /** A split tree. Width as a function of height is linear for every subtree: w = alpha * h + beta. */
    private static final class Node {
        final int leaf;
        final boolean row;
        final Node a;
        final Node b;
        double alpha;
        double beta;

        Node(int leaf) {
            this.leaf = leaf;
            this.row = false;
            this.a = null;
            this.b = null;
        }

        Node(boolean row, Node a, Node b) {
            this.leaf = -1;
            this.row = row;
            this.a = a;
            this.b = b;
        }
    }

    /**
     * Best guillotine layout under {@code scorer}, or null if every candidate is rejected. With
     * {@code keepOrder}, the join order wins when it is within 2% of the best, so tiles don't jump around.
     */
    private static int[][] search(double[] a, int width, int height, int gap, Scorer scorer, boolean keepOrder) {
        int n = a.length;
        if (n == 0) return new int[0][];
        int[][] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int[][] inOrder = null;
        double inOrderScore = Double.NEGATIVE_INFINITY;
        // Every order of up to four pictures (24) times every split tree is instant. Beyond that only the join
        // order is tried, and beyond six pictures there are too many trees: the caller falls back to the grid.
        if (n > 6) return null;
        List<int[]> orders = n <= 4 ? permutations(n) : java.util.Collections.singletonList(identity(n));
        for (int[] order : orders) {
            boolean identity = isIdentity(order);
            for (Node tree : trees(order, 0, n)) {
                int[][] rects = place(tree, a, width, height, gap, n);
                if (rects == null) continue;
                double s = scorer.score(rects);
                if (Double.isNaN(s)) continue;
                if (s > bestScore) {
                    bestScore = s;
                    best = rects;
                }
                if (identity && s > inOrderScore) {
                    inOrderScore = s;
                    inOrder = rects;
                }
            }
        }
        if (keepOrder && inOrder != null && inOrderScore >= bestScore * KEEP_ORDER_SLACK) return inOrder;
        return best;
    }

    private static int[][] place(Node root, double[] a, int width, int height, int gap, int n) {
        measure(root, a, gap);
        double h = Math.min(height, (width - root.beta) / root.alpha);
        if (h <= 0) return null;
        double w = root.alpha * h + root.beta;
        int[][] out = new int[n][];
        if (!assign(root, (width - w) / 2, (height - h) / 2, w, h, gap, out)) return null;
        return out;
    }

    private static void measure(Node node, double[] a, int gap) {
        if (node.leaf >= 0) {
            node.alpha = a[node.leaf];
            node.beta = 0;
            return;
        }
        measure(node.a, a, gap);
        measure(node.b, a, gap);
        if (node.row) {
            // Shared height h: w = (alpha_a + alpha_b) h + beta_a + beta_b + gap.
            node.alpha = node.a.alpha + node.b.alpha;
            node.beta = node.a.beta + node.b.beta + gap;
        } else {
            // Shared width w: h = (w - beta_a)/alpha_a + (w - beta_b)/alpha_b + gap, solved for w.
            double s = 1 / node.a.alpha + 1 / node.b.alpha;
            double c = node.a.beta / node.a.alpha + node.b.beta / node.b.alpha - gap;
            node.alpha = 1 / s;
            node.beta = c / s;
        }
    }

    private static boolean assign(Node node, double x, double y, double w, double h, int gap, int[][] out) {
        if (w < 1 || h < 1) return false;
        if (node.leaf >= 0) {
            out[node.leaf] = new int[]{(int) Math.round(x), (int) Math.round(y),
                    (int) Math.round(w), (int) Math.round(h)};
            return true;
        }
        if (node.row) {
            double wa = node.a.alpha * h + node.a.beta;
            return assign(node.a, x, y, wa, h, gap, out)
                    && assign(node.b, x + wa + gap, y, w - wa - gap, h, gap, out);
        }
        double ha = (w - node.a.beta) / node.a.alpha;
        return assign(node.a, x, y, w, ha, gap, out)
                && assign(node.b, x, y + ha + gap, w, h - ha - gap, gap, out);
    }

    private static List<Node> trees(int[] order, int from, int to) {
        List<Node> out = new ArrayList<>();
        if (to - from == 1) {
            out.add(new Node(order[from]));
            return out;
        }
        for (int k = from + 1; k < to; k++) {
            List<Node> left = trees(order, from, k);
            List<Node> right = trees(order, k, to);
            for (Node l : left) {
                for (Node r : right) {
                    out.add(new Node(true, l, r));
                    out.add(new Node(false, l, r));
                }
            }
        }
        return out;
    }

    private static List<int[]> permutations(int n) {
        List<int[]> out = new ArrayList<>();
        permute(new int[n], new boolean[n], 0, out);
        return out;
    }

    private static void permute(int[] cur, boolean[] used, int pos, List<int[]> out) {
        if (pos == cur.length) {
            out.add(cur.clone());
            return;
        }
        for (int i = 0; i < cur.length; i++) {
            if (used[i]) continue;
            used[i] = true;
            cur[pos] = i;
            permute(cur, used, pos + 1, out);
            used[i] = false;
        }
    }

    // ---- helpers ----

    private static int[] identity(int n) {
        int[] o = new int[n];
        for (int i = 0; i < n; i++) o[i] = i;
        return o;
    }

    private static boolean isIdentity(int[] order) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] != i) return false;
        }
        return true;
    }

    private static double[] clean(float[] aspects) {
        double[] a = new double[aspects.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = aspects[i] > 0.05f && aspects[i] < 20f ? aspects[i] : 16.0 / 9.0;
        }
        return a;
    }

    /** The largest rectangle of aspect {@code a} centred in the cell. */
    private static int[] fit(double a, int x, int y, int w, int h) {
        int fw = w;
        int fh = (int) Math.round(w / a);
        if (fh > h) {
            fh = h;
            fw = (int) Math.round(h * a);
        }
        return new int[]{x + (w - fw) / 2, y + (h - fh) / 2, fw, fh};
    }

    /** Moves the whole arrangement so its bounding box sits in the middle of the screen. */
    private static int[][] center(int[][] rects, int width, int height) {
        int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
        for (int[] x : rects) {
            l = Math.min(l, x[0]);
            t = Math.min(t, x[1]);
            r = Math.max(r, x[0] + x[2]);
            b = Math.max(b, x[1] + x[3]);
        }
        int dx = (width - (r - l)) / 2 - l;
        int dy = (height - (b - t)) / 2 - t;
        int[][] out = new int[rects.length][];
        for (int i = 0; i < rects.length; i++) {
            out[i] = new int[]{rects[i][0] + dx, rects[i][1] + dy, rects[i][2], rects[i][3]};
        }
        return out;
    }

    private static double area(int[] r) {
        return (double) r[2] * r[3];
    }

    private static double total(int[][] rects) {
        double t = 0;
        for (int[] r : rects) t += area(r);
        return t;
    }
}
