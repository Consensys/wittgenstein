package net.consensys.wittgenstein.core.utils;

public class GeneralizedParetoDistribution {
  /** shape ξ */
  private final double shape;

  /** location μ */
  private final double location;

  /** scale σ */
  private final double scale;

  private static final double ONE = 0.999999;
  private static final double ZERO = 0.000001;

  public GeneralizedParetoDistribution(double shape, double location, double scale) {
    if (scale <= 0.0) {
      throw new IllegalArgumentException("scale=" + scale);
    }

    this.shape = shape;
    this.location = location;
    this.scale = scale;
  }

  public double inverseF(double y) {
    if (y < 0.0 || y > 1.0) {
      throw new IllegalArgumentException("y=" + y);
    }

    if (y < ZERO) {
      return location;
    }

    if (y > ONE && shape >= 0) {
      return Double.POSITIVE_INFINITY;
    }

    if (y > ONE && shape < 0) {
      return location - scale / shape;
    }
    if (Math.abs(shape) < ZERO) {
      return location - scale * Math.log1p(-y);
    }
    return location + scale / shape * (-1 + Math.pow(1 - y, -shape));
  }

  @Override
  public String toString() {
    return "ξ=" + shape + ", μ=" + location + ", σ=" + scale;
  }
}
