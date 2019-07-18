package net.consensys.wittgenstein.core.geoinfo;

public class CityInfo {
  public final int mercX;
  public final int mercY;
  public final float cumulativeProbability;

  public CityInfo(int mercX, int mercY, float cumulativeProbability) {
    this.mercX = mercX;
    this.mercY = mercY;
    this.cumulativeProbability = cumulativeProbability;
  }
}
