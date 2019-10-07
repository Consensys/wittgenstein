package net.consensys.wittgenstein.core;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.Closeable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.consensys.wittgenstein.core.json.ExternalConverter;
import net.consensys.wittgenstein.core.utils.GeneralizedParetoDistribution;

@SuppressWarnings({"WeakerAccess"})
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
public class Node implements Closeable {
  public static final int MAX_X = 2000;
  public static final int MAX_Y = 1112;
  public static final int MAX_DIST =
      (int) Math.sqrt((MAX_X / 2.0) * (MAX_X / 2.0) + (MAX_Y / 2.0) * (MAX_Y / 2.0));
  public static final String DEFAULT_CITY = "world";

  /** Sequence without any holes; starts at zero. */
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
   * Some nodes can be behind Tor, or for any reason may suffer and extra latency (both in & out)
   * when they communicate. By default there is no such latency. This extra latency is a fixed
   * number of milliseconds.
   */
  public int extraLatency;

  /**
   * A protocol implementation may want to implement some byzantine behavior for some nodes. This
   * boolean marks, for statistics only, that this node is a byzantine node. It's not use anywhere
   * else in the framework.
   */
  public final boolean byzantine;

  /**
   * Some scenarios are interesting when the nodes are heterogeneous: Having some nodes lagging
   * behind in the network can be very troublesome for some protocols. A speed ratio of 1 (the
   * default) means this node is standard. It's possible to have faster or slower nodes. It's up to
   * the protocol implementer to use this parameter in its implementation. This parameter is NOT
   * used in the network layer itself: use the latency models if you want to act at the network
   * layer. A number greater then 1 represents a node slower than the standard.
   */
  public final double speedRatio;

  /** For some model latency we need to know in which town the node is. */
  public final String cityName;

  /**
   * A basic error scenario is a node down. When a node is down it cannot receive not send messages,
   * but the other nodes don't know about this.
   */
  private boolean down;

  /** The time when the protocol ended for this node 0 if it has not ended yet. */
  public long doneAt = 0;

  /** Some internal statistics. */
  protected long msgReceived = 0;

  protected long msgSent = 0;
  protected long bytesSent = 0;
  protected long bytesReceived = 0;

  private final AtomicInteger iuid;

  public int generateNewUniqueIntId() {
    return iuid.incrementAndGet();
  }

  @JsonSerialize(converter = ExternalConverter.class)
  private External external = null;

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

  /** Called when a node starts or restarts. */
  public void start() {
    down = false;
  }

  /** Called when a node is stopped. */
  public void stop() {
    down = true;
  }

  public boolean isDown() {
    return down;
  }

  public void setExternal(External ext) {
    this.external = ext;
  }

  public External getExternal() {
    return external;
  }

  /** If a node uses any extra resource it can free them here. */
  @Override
  public void close() {}

  public static class Aspect {
    public Object getObjectValue(Random rd) {
      return null;
    }
  }

  public static class ExtraLatencyAspect extends Aspect {
    final double ratio;

    public ExtraLatencyAspect(double ratio) {
      this.ratio = ratio;
    }

    public Object getObjectValue(Random rd) {
      return rd.nextDouble() < ratio ? 500 : 0;
    }
  }

  public static class SpeedRatioAspect extends Aspect {
    final SpeedModel sm;

    SpeedRatioAspect(SpeedModel sm) {
      this.sm = sm;
    }

    @Override
    public Object getObjectValue(Random rd) {
      return sm.getSpeedRatio(rd);
    }
  }

  /**
   * The SpeedModel allows model slow vs. fast nodes. By default, all the node have the same speed
   * ration (1.0), but it's possible to configure a network with slow (speed ratio > 1.0) or fast
   * (speed ratio < 1.0) nodes. It's up to the protocol to use this when it modelizes its internal
   * calculations.
   */
  public interface SpeedModel {
    double getSpeedRatio(Random rd);
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

  public static class GaussianSpeed implements SpeedModel {

    @Override
    public double getSpeedRatio(Random rd) {
      return Math.max(0.33, rd.nextGaussian() + 1);
    }

    @Override
    public String toString() {
      return this.getClass().getSimpleName();
    }
  }

  private static Object getAspectValue(
      Class<?> aspectClass, List<Aspect> aspects, Random rd, Object defVal) {
    for (Aspect aspect : aspects) {
      if (aspect.getClass().equals(aspectClass)) {
        return aspect.getObjectValue(rd);
      }
    }
    return defVal;
  }

  /**
   * We suppose that the computer speed are uniformly distributed from 3 times faster to 3 times
   * slower than the standard.
   */
  public static class UniformSpeed implements SpeedModel {

    @Override
    public double getSpeedRatio(Random rd) {
      return rd.nextBoolean() ? (rd.nextInt(67) + 33) / 100.0 : (rd.nextInt(200) + 100) / 100.0;
    }

    @Override
    public String toString() {
      return this.getClass().getSimpleName();
    }
  }

  public Node(Random rd, NodeBuilder nb, boolean byzantine) {
    this.iuid = nb.getUniqueIntIdReference();
    this.nodeId = nb.allocateNodeId();
    if (this.nodeId < 0) {
      throw new IllegalArgumentException("bad nodeId:" + nodeId);
    }
    int rdNode = rd.nextInt();
    this.cityName = nb.getCityName(rdNode);
    this.x = nb.getX(rdNode);
    this.y = nb.getY(rdNode);
    if (this.x <= 0 || this.x > MAX_X) {
      throw new IllegalArgumentException("bad x=" + x);
    }
    if (this.y <= 0 || this.y > MAX_Y) {
      throw new IllegalArgumentException("bad y=" + y);
    }
    this.byzantine = byzantine;
    this.hash256 = nb.getHash(nodeId);

    this.speedRatio = (double) getAspectValue(SpeedRatioAspect.class, nb.aspects, rd, 1.0);
    this.extraLatency = (int) getAspectValue(ExtraLatencyAspect.class, nb.aspects, rd, 0);

    if (speedRatio <= 0) {
      throw new IllegalArgumentException("speedRatio=" + speedRatio);
    }
  }

  public Node(Random rd, NodeBuilder nb) {
    this(rd, nb, false);
  }

  /** @return the distance with this node, considering a round map. */
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
