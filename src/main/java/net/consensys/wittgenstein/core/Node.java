package net.consensys.wittgenstein.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

@SuppressWarnings({"WeakerAccess"})
public class Node {
  public final static int MAX_X = 1000;
  public final static int MAX_Y = 1000;
  public final static int MAX_DIST =
      (int) Math.sqrt((MAX_X / 2) * (MAX_X / 2) + (MAX_Y / 2) * (MAX_Y / 2));


  public final int nodeId;
  public final byte[] hash256;

  /**
   * The position, from 1 to MAX_X / MAX_Y, included. There is a clear weakness here: the nodes are
   * randomly distributed, but in real life we have oceans, so some subset of nodes are really
   * isolated. We should have a builder that takes a map as an input.
   */
  public final int x;
  public final int y;
  public final boolean byzantine;

  protected long msgReceived = 0;
  protected long msgSent = 0;
  protected long bytesSent = 0;
  protected long bytesReceived = 0;

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

  public static class NodeBuilder {
    protected int nodeIds = 0;
    protected final MessageDigest digest;

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

    protected int getX() {
      return 1;
    }

    protected int getY() {
      return 1;
    }

    /**
     * Many algo will want a hash of the node id. Be careful: sha-3 is not the ethereum v1 hash.
     */
    protected byte[] getHash(int nodeId) {
      return digest.digest(ByteBuffer.allocate(4).putInt(nodeId).array());
    }
  }

  public static class NodeBuilderWithRandomPosition extends NodeBuilder {
    final Random rd;

    public NodeBuilderWithRandomPosition(Random rd) {
      this.rd = rd;
    }

    public int getX() {
      return rd.nextInt(MAX_X) + 1;
    }

    public int getY() {
      return rd.nextInt(MAX_Y) + 1;
    }
  }


  public Node(NodeBuilder nb, boolean byzantine) {
    this.nodeId = nb.allocateNodeId();
    if (this.nodeId < 0) {
      throw new IllegalArgumentException("bad nodeId:" + nodeId);
    }
    this.x = nb.getX();
    this.y = nb.getY();
    if (this.x <= 0 || this.x > MAX_X) {
      throw new IllegalArgumentException("bad x=" + x);
    }
    if (this.y <= 0 || this.y > MAX_Y) {
      throw new IllegalArgumentException("bad y=" + y);
    }
    this.byzantine = byzantine;
    this.hash256 = nb.getHash(nodeId);
  }

  public Node(NodeBuilder nb) {
    this(nb, false);
  }

  int dist(Node n) {
    int dx = Math.min(Math.abs(x - n.x), MAX_X - Math.abs(x - n.x));
    int dy = Math.min(Math.abs(y - n.y), MAX_Y - Math.abs(y - n.y));
    return (int) Math.sqrt(dx * dx + dy * dy);
  }


  /**
   * Many protocols finish at a point. A node can return the time it has ended.
   * 
   * @return network time the node ended its execution of the protocol, 0 if it is still running
   */
  public int doneAt() {
    return 0;
  }
}
