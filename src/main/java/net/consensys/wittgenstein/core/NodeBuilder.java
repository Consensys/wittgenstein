package net.consensys.wittgenstein.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@SuppressWarnings("WeakerAccess")
public class NodeBuilder implements Cloneable {
  private int nodeIds = 0;
  private final MessageDigest digest;
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

  protected int getX(Random rd) {
    return 1;
  }

  protected int getY(Random rd) {
    return 1;
  }

  protected String getCityName(Random rd) {
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
    public int getX(Random rd) {
      return rd.nextInt(Node.MAX_X) + 1;
    }

    @Override
    public int getY(Random rd) {
      return rd.nextInt(Node.MAX_Y) + 1;
    }
  }


  public static class NodeBuilderWithCity extends NodeBuilder {
    final List<String> cities;
    final int size;

    public NodeBuilderWithCity(List<String> cities) {
      this.cities = cities;
      this.size = cities.size();
    }

    @Override
    protected String getCityName(Random rd) {
      return cities.get(rd.nextInt(size));
    }
  }
}
