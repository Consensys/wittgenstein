package net.consensys.wittgenstein.protocols.handeleth2;

import net.consensys.wittgenstein.core.WParameters;

public class HandelEth2Parameters extends WParameters {
  public static final int PERIOD_TIME = 6000;
  public static final int PERIOD_AGG_TIME = PERIOD_TIME * 3;

  /** The number of nodes in the network */
  final int nodeCount;

  /** The minimum time it takes to do a pairing for a node. */
  final int pairingTime;

  int levelWaitTime;
  final int periodDurationMs;

  final int nodesDown;

  final String nodeBuilderName;
  final String networkLatencyName;

  /**
   * Allows to test what happens when all the nodes are not starting at the same time. If the value
   * is different than 0, all the nodes are starting at a time uniformly distributed between zero
   * and 'desynchronizedStart'
   */
  int desynchronizedStart;

  // Used for json / http server
  @SuppressWarnings("unused")
  public HandelEth2Parameters() {
    this.nodeCount = 64;
    this.pairingTime = 3;
    this.levelWaitTime = 100;
    this.periodDurationMs = 50;
    this.nodesDown = 0;
    this.nodeBuilderName = null;
    this.networkLatencyName = null;
    this.desynchronizedStart = 0;
  }

  public HandelEth2Parameters(
      int nodeCount,
      int pairingTime,
      int levelWaitTime,
      int periodDurationMs,
      int nodesDown,
      String nodeBuilderName,
      String networkLatencyName,
      int desynchronizedStart) {

    if (nodesDown >= nodeCount || nodesDown < 0) {
      throw new IllegalArgumentException("nodeCount=" + nodeCount);
    }
    if (Integer.bitCount(nodeCount) != 1) {
      throw new IllegalArgumentException("We support only power of two nodes in this simulation");
    }

    this.nodeCount = nodeCount;
    this.pairingTime = pairingTime;
    this.levelWaitTime = levelWaitTime;
    this.periodDurationMs = periodDurationMs;
    this.nodesDown = nodesDown;
    this.nodeBuilderName = nodeBuilderName;
    this.networkLatencyName = networkLatencyName;
    this.desynchronizedStart = desynchronizedStart;
  }
}
