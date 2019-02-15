package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.utils.GeneralizedParetoDistribution;
import java.util.Random;

@SuppressWarnings({"WeakerAccess"})
public class Node {
  public final static int MAX_X = 1000;
  public final static int MAX_Y = 1000;
  public final static int MAX_DIST =
      (int) Math.sqrt((MAX_X / 2.0) * (MAX_X / 2.0) + (MAX_Y / 2.0) * (MAX_Y / 2.0));
  public final static String DEFAULT_CITY = "world";

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

  /**
   * A protocol implementation may want to implement some byzantine behavior for some nodes. This
   * boolean marks, for statistics only, that this node is a byzantine node. It's not use anywhere
   * else in the framework.
   */
  public final boolean byzantine;

  /**
   * A basic error scenario is a node down. When a node is down it cannot receive not send messages,
   * but the other nodes don't know about this.
   */
  public boolean down;

  /**
   * Some scenarios are interesting when the nodes are heterogeneous: Having some nodes lagging
   * behind in the network can be very troublesome for some protocols. A speed ratio of 1 (the
   * default) means this node is standard. It's possible to have faster or slower nodes. It's up to
   * the protocol implementer to use this parameter in its implementation. This parameter is NOT
   * used in the network layer itself: use the latency models if you want to act at the network
   * layer. A number greater then 1 represents a node slower than the standard.
   */
  public double speedRatio;

  protected long msgReceived = 0;
  protected long msgSent = 0;
  protected long bytesSent = 0;
  protected long bytesReceived = 0;
  public String cityName;
  boolean external = false;

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

  public String fullToString() {
    return "id=" + nodeId + ", city=" + cityName + ", posX=" + x + ", posY=" + y;
  }

  /**
   * Called when a node starts or restarts.
   */
  public void start() {
    down = false;
  }

  /**
   * Called when a node is stopped.
   */
  public void stop() {
    down = true;
  }


  public void setExternal(boolean external) {
    this.external = external;
  }


  /**
   * The SpeedModel allows model slow vs. fast nodes. By default, all the node have the same speed
   * ration (1.0), but it's possible to configure a network with slow (speed ratio > 1.0) or fast
   * (speed ratio < 1.0) nodes. It's up to the protocol to use this when it modelize its internal
   * calculations.
   */
  public interface SpeedModel {
    double getSpeedRatio(Random rd);
  }


  public static class ConstantSpeed implements SpeedModel {
    @Override
    public double getSpeedRatio(Random rd) {
      return 1.0;
    }

    @Override
    public String toString() {
      return this.getClass().getSimpleName();
    }
  }


  public static class ParetoSpeed implements SpeedModel {
    final GeneralizedParetoDistribution gpd;
    final double max;

    public ParetoSpeed(double shape, double location, double scale, double max) {
      this.gpd = new GeneralizedParetoDistribution(shape, location, scale);
      this.max = max;
    }

    @Override
    public double getSpeedRatio(Random rd) {
      return Math.min(max, 1.0 + gpd.inverseF(rd.nextDouble()));
    }

    @Override
    public String toString() {
      return "GeneralizedParetoDistributionSpeed, max=" + max + ", " + gpd;
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
    this.speedRatio = nb.getSpeedRatio(rd);
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

  @Override
  public boolean equals(Object n) {
    if (!(n instanceof Node)) {
      return false;
    }
    return ((Node) n).nodeId == nodeId;
  }
}
