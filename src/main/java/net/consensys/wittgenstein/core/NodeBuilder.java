package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.geoinfo.CityGeoInfo;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static net.consensys.wittgenstein.core.Node.MAX_X;
import static net.consensys.wittgenstein.core.Node.MAX_Y;

@SuppressWarnings("WeakerAccess")
public class NodeBuilder implements Cloneable {
  /**
   * Last node id allocated.
   */
  private int nodeIds = 0;
  /**
   * Used to calculate a hash
   */
  private final MessageDigest digest;
  /**
   * List of the aspects we can add to the node (speed, latency, ...)
   */
  public final List<Node.Aspect> aspects = new ArrayList<>();

  public NodeBuilder() {
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException();
    }
  }

  /**
   * Same node builder with the node ids reset to zero, allowing to construct another network with
   * the same parameters.
   */
  public NodeBuilder copy() {
    try {
      NodeBuilder nb = (NodeBuilder) this.clone();
      nb.nodeIds = 0;
      return nb;
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException(e);
    }
  }

  int allocateNodeId() {
    return nodeIds++;
  }

  protected int getX(int rdInt) {
    return 1;
  }

  protected int getY(int rdInt) {
    return 1;
  }

  protected String getCityName(int rdInt) {
    return Node.DEFAULT_CITY;
  }

  /**
   * Many protocols wants a hash of the node id.
   */
  protected byte[] getHash(int nodeId) {
    return digest.digest(ByteBuffer.allocate(4).putInt(nodeId).array());
  }

  public static class NodeBuilderWithRandomPosition extends NodeBuilder {

    public NodeBuilderWithRandomPosition() {}

    @Override
    public int getX(int rdInt) {
      return Math.abs(rdInt >> 16) % MAX_X + 1;
    }

    @Override
    public int getY(int rdInt) {
      return Math.abs(rdInt << 16) % MAX_Y + 1;
    }
  }


  public static class NodeBuilderWithCity extends NodeBuilder {
    final Map<String, int[]> citiesPosition;
    final List<String> cities;

    public NodeBuilderWithCity(List<String> cities, CityGeoInfo geoInfo) {
      this.cities = cities;
      citiesPosition = geoInfo.citiesPosition();
    }

    @Override
    protected String getCityName(int rdInt) {
      return cities.get(Math.abs(rdInt) % cities.size());
    }

    int[] getPos(int rdInt) {
      String city = getCityName(rdInt);
      int[] pos = citiesPosition.get(city.toLowerCase());
      return Objects.requireNonNullElseGet(pos, () -> new int[] {1, 1});
    }

    protected int getX(int rdInt) {
      return getPos(rdInt)[0];
    }

    protected int getY(int rdInt) {
      return getPos(rdInt)[1];
    }
  }
}
