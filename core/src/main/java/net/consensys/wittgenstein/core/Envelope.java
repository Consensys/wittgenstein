package net.consensys.wittgenstein.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.messages.Message;

/** This is a class internal to the framework. */
@SuppressWarnings("WeakerAccess")
abstract class Envelope<TN extends Node> {
  final int sendTime;

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

  EnvelopeInfo<?> curInfos(Network<?> network) {
    return new EnvelopeInfo<>(
        getFromId(), getNextDestId(), sendTime, nextArrivalTime(network), getMessage());
  }

  abstract List<EnvelopeInfo<?>> infos(Network<?> network);

  public Envelope(int sendTime) {
    this.sendTime = sendTime;
  }

  /**
   * The implementation idea here is the following: - we expect that messages are the bottleneck -
   * we expect that we have a lot of single messages sent to multiple nodes, many thousands - this
   * has been confirmed by looking at the behavior with yourkit 95% of the memory is messages - so
   * we want to optimize this case. - we have a single MultipleDestEnvelope for all nodes - we don't
   * keep the list of the network latency to save memory
   *
   * <p>To avoid storing the network latencies, we do: - generate the randomness from a unique per
   * MultipleDestEnvelope + the node id - sort the nodes with the calculated latency (hence the
   * first node is the first to receive the message) - recalculate them on the fly as the nodeId &
   * the randomSeed are kept. - this also allows on disk serialization
   */
  static class MultipleDestEnvelope<TN extends Node> extends Envelope<TN> {
    final Message<TN> message;
    private final int fromNodeId;

    final int randomSeed;
    private final int[] destIds;
    protected int curPos = 0;
    private Envelope<?> nextSameTime = null;

    MultipleDestEnvelope(
        Message<TN> m,
        Node fromNode,
        List<Network.MessageArrival> dests,
        int sendTime,
        int randomSeed) {
      super(sendTime);
      this.message = m;
      this.fromNodeId = fromNode.nodeId;
      this.randomSeed = randomSeed;
      this.destIds = new int[dests.size()];

      for (int i = 0; i < destIds.length; i++) {
        destIds[i] = dests.get(i).dest.nodeId;
      }
    }

    @Override
    public String toString() {
      return "Envelope{"
          + "message="
          + message
          + ", fromNode="
          + fromNodeId
          + ", dests="
          + Arrays.toString(destIds)
          + ", curPos="
          + curPos
          + '}';
    }

    @Override
    Message<TN> getMessage() {
      return message;
    }

    @Override
    int getNextDestId() {
      return destIds[curPos];
    }

    private int arrivalTime(Network<?> network, int destId) {
      int rd = Network.getPseudoRandom(destId, randomSeed);
      Node f = network.getNodeById(this.fromNodeId);
      Node t = network.getNodeById(destId);
      int lat = network.networkLatency.getLatency(f, t, rd);
      return sendTime + lat;
    }

    @Override
    int nextArrivalTime(Network<?> network) {
      return arrivalTime(network, getNextDestId());
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

    @Override
    List<EnvelopeInfo<?>> infos(Network<?> network) {
      List<EnvelopeInfo<?>> res = new ArrayList<>();
      for (int i = curPos; i < destIds.length; i++) {
        EnvelopeInfo<?> ei =
            new EnvelopeInfo<>(
                fromNodeId, destIds[i], sendTime, arrivalTime(network, destIds[i]), message);
        res.add(ei);
      }
      return res;
    }
  }

  static final class MultipleDestWithDelayEnvelope<TN extends Node> extends Envelope<TN> {
    final Message<TN> message;
    private final int fromNodeId;

    private final int[] destIds;
    private final int[] arrivalTime;
    protected int curPos = 0;
    private Envelope<?> nextSameTime = null;

    MultipleDestWithDelayEnvelope(
        Message<TN> m, Node fromNode, List<Network.MessageArrival> dests, int sendTime) {
      super(sendTime);
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

    @Override
    List<EnvelopeInfo<?>> infos(Network<?> network) {
      List<EnvelopeInfo<?>> res = new ArrayList<>();
      for (int i = curPos; i < destIds.length; i++) {
        EnvelopeInfo<?> ei =
            new EnvelopeInfo<>(fromNodeId, destIds[i], sendTime, arrivalTime[i], message);
        res.add(ei);
      }
      return res;
    }
  }

  static final class SingleDestEnvelope<TN extends Node> extends Envelope<TN> {
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

    SingleDestEnvelope(
        Message<TN> message, Node fromNode, Node toNode, int sendTime, int arrivalTime) {
      super(sendTime);
      this.message = message;
      this.fromNodeId = fromNode.nodeId;
      this.toNodeId = toNode.nodeId;
      this.arrivalTime = arrivalTime;
    }

    @Override
    public String toString() {
      return "Envelope{"
          + "message="
          + message
          + ", fromNode="
          + fromNodeId
          + ", dest="
          + toNodeId
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

    @Override
    int nextArrivalTime(Network network) {
      return arrivalTime;
    }

    @Override
    void markRead() {}

    @Override
    boolean hasNextReader() {
      return false;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }

    @Override
    List<EnvelopeInfo<?>> infos(Network<?> network) {
      return Collections.singletonList(
          new EnvelopeInfo<>(fromNodeId, toNodeId, sendTime, arrivalTime, message));
    }
  }
}
