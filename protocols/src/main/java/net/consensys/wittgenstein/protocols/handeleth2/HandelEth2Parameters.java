package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.BitSet;
import net.consensys.wittgenstein.core.WParameters;

public class HandelEth2Parameters extends WParameters {
  /** The number of nodes in the network */
  final int nodeCount;

  /** The number of signatures to reach to finish the protocol. */
  final int threshold;

  /** The minimum time it takes to do a pairing for a node. */
  final int pairingTime;

  int levelWaitTime;
  final int periodDurationMs;
  int acceleratedCallsCount;

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
    this.nodeCount = 32768 / 1024;
    this.threshold = (int) (nodeCount * (0.99));
    this.pairingTime = 3;
    this.levelWaitTime = 50;
    this.periodDurationMs = 10;
    this.acceleratedCallsCount = 10;
    this.nodesDown = 0;
    this.nodeBuilderName = null;
    this.networkLatencyName = null;
    this.desynchronizedStart = 0;
  }

  public HandelEth2Parameters(
      int nodeCount,
      int threshold,
      int pairingTime,
      int levelWaitTime,
      int periodDurationMs,
      int acceleratedCallsCount,
      int nodesDown,
      String nodeBuilderName,
      String networkLatencyName,
      int desynchronizedStart,
      boolean byzantineSuicide,
      boolean hiddenByzantine,
      String bestLevelFunction,
      BitSet badNodes) {

    if (nodesDown >= nodeCount
        || nodesDown < 0
        || threshold > nodeCount
        || (nodesDown + threshold > nodeCount)) {
      throw new IllegalArgumentException("nodeCount=" + nodeCount + ", threshold=" + threshold);
    }
    if (Integer.bitCount(nodeCount) != 1) {
      throw new IllegalArgumentException("We support only power of two nodes in this simulation");
    }

    if (byzantineSuicide && hiddenByzantine) {
      throw new IllegalArgumentException("Only one attack at a time");
    }

    this.nodeCount = nodeCount;
    this.threshold = threshold;
    this.pairingTime = pairingTime;
    this.levelWaitTime = levelWaitTime;
    this.periodDurationMs = periodDurationMs;
    this.acceleratedCallsCount = acceleratedCallsCount;
    this.nodesDown = nodesDown;
    this.nodeBuilderName = nodeBuilderName;
    this.networkLatencyName = networkLatencyName;
    this.desynchronizedStart = desynchronizedStart;
  }
}
