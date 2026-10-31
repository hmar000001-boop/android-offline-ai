package dev.filterbridge.core;

/** Bounding box normalized to the analyzed image/frame dimensions (coordinates in [0, 1]). */
public final class NormalizedBox {
    private final double left;
    private final double top;
    private final double right;
    private final double bottom;

    public NormalizedBox(double left, double top, double right, double bottom) {
        requireCoordinate(left, "left");
        requireCoordinate(top, "top");
        requireCoordinate(right, "right");
        requireCoordinate(bottom, "bottom");
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("A box must have positive width and height");
        }
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public double left() { return left; }
    public double top() { return top; }
    public double right() { return right; }
    public double bottom() { return bottom; }
    public double width() { return right - left; }
    public double height() { return bottom - top; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NormalizedBox)) return false;
        NormalizedBox that = (NormalizedBox) other;
        return Double.compare(left, that.left) == 0
                && Double.compare(top, that.top) == 0
                && Double.compare(right, that.right) == 0
                && Double.compare(bottom, that.bottom) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(left, top, right, bottom);
    }

    private static void requireCoordinate(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " must be a finite normalized coordinate in [0, 1]");
        }
    }
}
