package net.consensys.wittgenstein.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
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
    final Map<String, int[]> citiesPosition = new HashMap<>();
    final List<String> cities;

    public NodeBuilderWithCity(List<String> cities) {
      this.cities = cities;

      // We have only the positions for the cities
      //  in the AWS network latency object
      citiesPosition.put("oregon", new int[] {424, 325});
      citiesPosition.put("virginia", new int[] {294, 301});
      citiesPosition.put("mumbai", new int[] {1360, 440});
      citiesPosition.put("seoul", new int[] {1641, 312});
      citiesPosition.put("singapour", new int[] {1486, 580});
      citiesPosition.put("sydney", new int[] {1773, 777});
      citiesPosition.put("tokyo", new int[] {1708, 306});
      citiesPosition.put("canada central", new int[] {422, 256});
      citiesPosition.put("frankfurt", new int[] {1018, 220});
      citiesPosition.put("ireland", new int[] {891, 200});
      citiesPosition.put("london", new int[] {937, 205});
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
