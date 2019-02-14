package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.messages.Message;
import java.util.Arrays;
import java.util.List;

/**
 * This is a class internal to the framework.
 */
abstract class Envelope<TN extends Node> {
  abstract Message<TN> getMessage();

  abstract int getNextDestId();

  abstract int nextArrivalTime(Network<?> network);

  /**
   * The next envelop arriving at the same time, if any: we have this because we manage the envelop
   * as a linked list.
   */
  abstract Envelope<?> getNextSameTime();

  abstract void setNextSameTime(Envelope<?> m);

  abstract void markRead();

  abstract boolean hasNextReader();

  abstract int getFromId();

  /**
   * The implementation idea here is the following: - we expect that messages are the bottleneck -
   * we expect that we have a lot of single messages sent to multiple nodes, many thousands - this
   * has been confirmed by looking at the behavior with yourkit 95% of the memory is messages - so we
   * want to optimize this case. - we have a single MultipleDestEnvelope for all nodes - we don't
   * keep the list of the network latency to save memory
   * <p>
   * To avoid storing the network latencies, we do: - generate the randomness from a unique per
   * MultipleDestEnvelope + the node id - sort the nodes with the calculated latency (hence the
   * first node is the first to receive the message) - recalculate them on the fly as the nodeId &
   * the randomSeed are kept. - this also allows on disk serialization
   */
  static class MultipleDestEnvelope<TN extends Node> extends Envelope<TN> {
    final Message<TN> message;
    private final int fromNodeId;

    private final int sendTime;
    final int randomSeed;
    private final int[] destIds;
    protected int curPos = 0;
    private Envelope<?> nextSameTime = null;

    MultipleDestEnvelope(Message<TN> m, Node fromNode, List<Network.MessageArrival> dests,
        int sendTime, int randomSeed) {
      this.message = m;
      this.fromNodeId = fromNode.nodeId;
      this.randomSeed = randomSeed;
      this.destIds = new int[dests.size()];

      for (int i = 0; i < destIds.length; i++) {
        destIds[i] = dests.get(i).dest.nodeId;
      }
      this.sendTime = sendTime;
    }

    @Override
    public String toString() {
      return "Envelope{" + "message=" + message + ", fromNode=" + fromNodeId + ", dests="
          + Arrays.toString(destIds) + ", curPos=" + curPos + '}';
    }

    @Override
    Message<TN> getMessage() {
      return message;
    }

    @Override
    int getNextDestId() {
      return destIds[curPos];
    }

    @Override
    int nextArrivalTime(Network<?> network) {
      int rd = Network.getPseudoRandom(this.getNextDestId(), randomSeed);
      Node f = network.getNodeById(this.fromNodeId);
      Node t = network.getNodeById(this.getNextDestId());
      int lat = network.networkLatency.getLatency(f, t, rd);
      return sendTime + lat;
    }

    @Override
    Envelope<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Envelope<?> m) {
      this.nextSameTime = m;
    }

    void markRead() {
      curPos++;
    }

    @Override
    boolean hasNextReader() {
      return curPos < destIds.length;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }
  }


  final static class MultipleDestWithDelayEnvelope<TN extends Node> extends Envelope<TN> {
    final Message<TN> message;
    private final int fromNodeId;

    private final int[] destIds;
    private final int[] arrivalTime;
    protected int curPos = 0;
    private Envelope<?> nextSameTime = null;

    MultipleDestWithDelayEnvelope(Message<TN> m, Node fromNode,
        List<Network.MessageArrival> dests) {
      this.message = m;
      this.fromNodeId = fromNode.nodeId;
      this.destIds = new int[dests.size()];
      this.arrivalTime = new int[dests.size()];
      for (int i = 0; i < destIds.length; i++) {
        destIds[i] = dests.get(i).dest.nodeId;
        arrivalTime[i] = dests.get(i).arrival;
      }
    }

    @Override
    Message<TN> getMessage() {
      return message;
    }

    @Override
    int getNextDestId() {
      return destIds[curPos];
    }

    int nextArrivalTime(Network<?> network) {
      return arrivalTime[curPos];
    }

    @Override
    Envelope<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Envelope<?> m) {
      nextSameTime = m;
    }

    @Override
    void markRead() {
      curPos++;
    }

    @Override
    boolean hasNextReader() {
      return curPos < destIds.length;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }
  }


  final static class SingleDestEnvelope<TN extends Node> extends Envelope<TN> {
    final Message<TN> message;
    private final int fromNodeId;
    private final int toNodeId;
    private final int arrivalTime;
    private Envelope<?> nextSameTime = null;


    @Override
    Envelope<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Envelope<?> nextSameTime) {
      this.nextSameTime = nextSameTime;
    }

    SingleDestEnvelope(Message<TN> message, Node fromNode, Node toNode, int arrivalTime) {
      this.message = message;
      this.fromNodeId = fromNode.nodeId;
      this.toNodeId = toNode.nodeId;
      this.arrivalTime = arrivalTime;
    }

    @Override
    public String toString() {
      return "Envelope{" + "message=" + message + ", fromNode=" + fromNodeId + ", dest=" + toNodeId
          + '}';
    }

    @Override
    Message<TN> getMessage() {
      return message;
    }

    @Override
    int getNextDestId() {
      return toNodeId;
    }

    int nextArrivalTime(Network network) {
      return arrivalTime;
    }

    void markRead() {}

    boolean hasNextReader() {
      return false;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }
  }
}
