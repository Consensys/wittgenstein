package net.consensys.wittgenstein.core;

import java.util.Arrays;
import java.util.List;

/**
 * This is a class internal to the framework.
 */
abstract class Message<TN extends Node> {
  abstract Network.MessageContent<TN> getMessageContent();

  abstract int getNextDestId();

  abstract int nextArrivalTime(Network network);

  abstract Message<?> getNextSameTime();

  abstract void setNextSameTime(Message<?> m);

  abstract void markRead();

  abstract boolean hasNextReader();

  abstract int getFromId();

  /**
   * The implementation idea here is the following: - we expect that messages are the bottleneck -
   * we expect that we have a lot of single messages sent to multiple nodes, many thousands - this
   * has been confirmed by looking at the behavior with youkit 95% of the memory is messages - so we
   * want to optimize this case. - we have a single MultipleDestMessage for all nodes - we don't
   * keep the list of the network latency to save memory
   * <p>
   * To avoid storing the network latencies, we do: - generate the randomness from a unique per
   * MultipleDestMessage + the node id - sort the nodes with the calculated latency (hence the first
   * node is the first to receive the message) - recalculate them on the fly as the nodeId & the
   * randomSeed are kept. - this also allows on disk serialization
   */
  final static class MultipleDestMessage<TN extends Node> extends Message<TN> {
    final Network.MessageContent<TN> messageContent;
    private final int fromNodeId;

    private final int sendTime;
    final int randomSeed;
    private final int[] destIds;
    private int curPos = 0;
    private Message<?> nextSameTime = null;

    MultipleDestMessage(Network.MessageContent<TN> m, Node fromNode,
        List<Network.MessageArrival> dests, int sendTime, int randomSeed) {
      this.messageContent = m;
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
      return "Message{" + "messageContent=" + messageContent + ", fromNode=" + fromNodeId
          + ", dests=" + Arrays.toString(destIds) + ", curPos=" + curPos + '}';
    }

    @Override
    Network.MessageContent<TN> getMessageContent() {
      return messageContent;
    }

    @Override
    int getNextDestId() {
      return destIds[curPos];
    }

    int nextArrivalTime(Network network) {
      return sendTime
          + network.networkLatency.getLatency((Node) network.allNodes.get(this.fromNodeId),
              (Node) network.allNodes.get(this.getNextDestId()),
              Network.getPseudoRandom(this.getNextDestId(), randomSeed));
    }

    @Override
    Message<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Message<?> m) {
      this.nextSameTime = m;
    }

    void markRead() {
      curPos++;
    }

    boolean hasNextReader() {
      return curPos < destIds.length;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }
  }


  final static class SingleDestMessage<TN extends Node> extends Message<TN> {
    final Network.MessageContent<TN> messageContent;
    private final int fromNodeId;
    private final int toNodeId;
    private final int arrivalTime;
    private Message<?> nextSameTime = null;


    @Override
    Message<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Message<?> nextSameTime) {
      this.nextSameTime = nextSameTime;
    }

    SingleDestMessage(Network.MessageContent<TN> messageContent, Node fromNode, Node toNode,
        int arrivalTime) {
      this.messageContent = messageContent;
      this.fromNodeId = fromNode.nodeId;
      this.toNodeId = toNode.nodeId;
      this.arrivalTime = arrivalTime;
    }

    @Override
    public String toString() {
      return "Message{" + "messageContent=" + messageContent + ", fromNode=" + fromNodeId
          + ", dest=" + toNodeId + '}';
    }

    @Override
    Network.MessageContent<TN> getMessageContent() {
      return messageContent;
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
