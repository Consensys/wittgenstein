package net.consensys.wittgenstein.core;

import java.util.*;
import java.util.stream.Collectors;

public class P2PNetwork<TN extends P2PNode<TN>> extends Network<TN> {
  private final int connectionCount;
  private final boolean minimum;

  /**
   * @param connectionCount - the target for the number of connection
   * @param minimum - if true, connectionCount is the minimum number of connections per node. If
   *        false, it's the average number of nodes, with a minimum of 3 connections per node.
   */
  public P2PNetwork(int connectionCount, boolean minimum) {
    this.connectionCount = connectionCount;
    this.minimum = minimum;
  }

  public void setPeers() {
    Set<Long> existingLinks = new HashSet<>();
    if (!minimum) {
      int toCreate = (allNodes.size() * connectionCount) / 2;
      while (toCreate != existingLinks.size()) {
        int pp1 = rd.nextInt(allNodes.size());
        int pp2 = rd.nextInt(allNodes.size());
        createLink(existingLinks, pp1, pp2);
      }
    }

    // We need to go through the list in a random order, if not we can
    //  have some side effects if all the dead nodes are at the beginning for example.
    // For this reason we work on a shuffled version of the node list.
    ArrayList<TN> an = new ArrayList<>(allNodes);
    Collections.shuffle(an, rd);
    for (TN n : an) {
      while (n.peers.size() < (minimum ? connectionCount : Math.min(3, this.connectionCount))) {
        int pp2 = rd.nextInt(allNodes.size());
        createLink(existingLinks, n.nodeId, pp2);
      }
    }
  }

  private void createLink(Set<Long> existingLinks, int pp1, int pp2) {
    if (pp1 == pp2) {
      return;
    }
    long l1 = Math.min(pp1, pp2);
    long l2 = Math.max(pp1, pp2);
    long link = (l1 << 32) + l2;
    if (!existingLinks.add(link)) {
      return;
    }

    TN p1 = allNodes.get(pp1);
    TN p2 = allNodes.get(pp2);

    if (p1 == null || p2 == null) {
      throw new IllegalStateException(
          "should not be null: p1=" + p1 + ", p2=" + p2 + ", pp1=" + pp1 + ", pp2=" + pp2);
    }

    p1.peers.add(p2);
    p2.peers.add(p1);
  }

  public int avgPeers() {
    if (allNodes.isEmpty()) {
      return 0;
    }

    long tot = 0;
    for (TN n : allNodes) {
      tot += n.peers.size();
    }
    return (int) (tot / allNodes.size());
  }


  public void sendPeers(FloodMessage msg, TN from) {
    from.getSet(msg.msgId()).add(msg);
    List<TN> dest = new ArrayList<>(from.peers);
    Collections.shuffle(dest, rd);
    send(msg, time + 1 + msg.localDelay, from, dest, msg.delayBetweenPeers);
  }

  /**
   * A P2P node supports flood by default.
   */
  public class FloodMessage extends Network.Message<TN> {
    protected final int size;
    /**
     * The delay before we send this message to the other nodes, for example if we need to validate
     * the message before diffusing it.
     */
    protected final int localDelay;
    /**
     * It's possible to send the message immediately to all peers, but as well to wait between
     * peers.
     */
    protected final int delayBetweenPeers;

    protected long msgId() {
      return -1;
    }

    public FloodMessage(int size, int localDelay, int delayBetweenPeers) {
      this.size = size;
      this.localDelay = localDelay;
      this.delayBetweenPeers = delayBetweenPeers;
    }

    @Override
    public void action(TN from, TN to) {
      if (to.getSet(msgId()).add(this)) {
        to.onFlood(from, this);
        List<TN> dest = to.peers.stream().filter(n -> n != from).collect(Collectors.toList());
        Collections.shuffle(dest, rd);
        send(this, time + 1 + localDelay, to, dest, delayBetweenPeers);
      }
    }

    @Override
    public int size() {
      return size;
    }
  }


  /**
   * Class to use when a same node will update the message, i.e. the new version will replace the
   * old ones.
   */
  public class StatusFloodMessage extends FloodMessage {
    /**
     * The message id. It's the same for all versions. The message id must be globally unique, i.e.
     * if multiple nodes are sending the same type of message they need to have two different msg
     * id.
     */
    final int msgId;
    /**
     * The version number.
     */
    final int seq;

    public StatusFloodMessage(int msgId, int seq, int size, int localDelay, int delayBetweenPeers) {
      super(size, localDelay, delayBetweenPeers);
      if (msgId < 0) {
        throw new IllegalStateException("id less than zero are reserved, msgId=" + msgId);
      }
      this.msgId = msgId;
      this.seq = seq;
    }

    protected long msgId() {
      return msgId;
    }

    @Override
    public final void action(TN from, TN to) {
      Set<?> previousSet = to.getSet(msgId);

      Object previous = previousSet.isEmpty() ? null : previousSet.iterator().next();
      StatusFloodMessage psf = (StatusFloodMessage) previous;
      if (psf == null || psf.seq < seq) {
        previousSet.clear();
        to.getSet(msgId).add(this);
        to.onFlood(from, this);
        List<TN> dest = to.peers.stream().filter(n -> n != from).collect(Collectors.toList());
        Collections.shuffle(dest, rd);
        send(this, time + 1 + localDelay, to, dest, delayBetweenPeers);
      }
    }
  }
}
