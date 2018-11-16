package net.consensys.wittgenstein.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;

@SuppressWarnings({"WeakerAccess"})
public class Node {
  public final static int MAX_X = 1000;
  public final static int MAX_Y = 1000;
  public final static int MAX_DIST =
      (int) Math.sqrt((MAX_X / 2) * (MAX_X / 2) + (MAX_Y / 2) * (MAX_Y / 2));


  /**
   * Sequence without any holes; starts at zero.
   */
  public final int nodeId;

  /**
   * Many algorithms will want to identify a node by a large & unique number. We do it by default.
   */
  public final byte[] hash256;

  /**
   * The position, from 1 to MAX_X / MAX_Y, included. There is a clear weakness here: the nodes are
   * randomly distributed, but in real life we have oceans, so some subset of nodes are really
   * isolated. We should have a builder that takes a map as an input.
   */
  public final int x;
  public final int y;
  public final boolean byzantine; // Used in statistics only
  public boolean down; // Cannot send or receive messages

  protected long msgReceived = 0;
  protected long msgSent = 0;
  protected long bytesSent = 0;
  protected long bytesReceived = 0;
  public String cityName;

  /**
   * The time when the protocol ended for this node 0 if it has not ended yet.
   */
  public long doneAt = 0;

  public long getMsgReceived() {
    return msgReceived;
  }

  public long getMsgSent() {
    return msgSent;
  }

  public long getBytesSent() {
    return bytesSent;
  }

  public long getBytesReceived() {
    return bytesReceived;
  }

  public long getDoneAt() {
    return doneAt;
  }

  @Override
  public String toString() {
    return "Node{" + "nodeId=" + nodeId + '}';
  }

  public static class NodeBuilder {
    protected int nodeIds = 0;
    protected final MessageDigest digest;
    private String cityName = "World";

    public NodeBuilder() {
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException();
      }
    }

    protected int allocateNodeId() {
      return nodeIds++;
    }

    protected int getX(Random rd) {
      return 1;
    }

    protected int getY(Random rd) {
      return 1;
    }

    protected String getCityName(Random rd) {
      return cityName;
    }



    /**
     * Many algo will want a hash of the node id. Be careful: sha-3 is not the ethereum v1 hash.
     */
    protected byte[] getHash(int nodeId) {
      return digest.digest(ByteBuffer.allocate(4).putInt(nodeId).array());
    }
  }


  public static class NodeBuilderWithRandomPosition extends NodeBuilder {
    @Override
    public int getX(Random rd) {
      return rd.nextInt(MAX_X) + 1;
    }

    @Override
    public int getY(Random rd) {
      return rd.nextInt(MAX_Y) + 1;
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


  public Node(Random rd, NodeBuilder nb, boolean byzantine) {
    this.nodeId = nb.allocateNodeId();
    if (this.nodeId < 0) {
      throw new IllegalArgumentException("bad nodeId:" + nodeId);
    }
    this.x = nb.getX(rd);
    this.y = nb.getY(rd);
    if (this.x <= 0 || this.x > MAX_X) {
      throw new IllegalArgumentException("bad x=" + x);
    }
    if (this.y <= 0 || this.y > MAX_Y) {
      throw new IllegalArgumentException("bad y=" + y);
    }
    this.byzantine = byzantine;
    this.hash256 = nb.getHash(nodeId);
    this.cityName = nb.getCityName(rd);
  }

  public Node(Random rd, NodeBuilder nb) {
    this(rd, nb, false);
  }


  /**
   * @return the distance with this node, considering a round map.
   */
  int dist(Node n) {
    int dx = Math.min(Math.abs(x - n.x), MAX_X - Math.abs(x - n.x));
    int dy = Math.min(Math.abs(y - n.y), MAX_Y - Math.abs(y - n.y));
    return (int) Math.sqrt(dx * dx + dy * dy);
  }
}
