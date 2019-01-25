package net.consensys.wittgenstein.core;

import java.util.*;
import java.util.stream.Collectors;

public class P2PNetwork extends Network<P2PNode> {
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
    ArrayList<P2PNode> an = new ArrayList<>(allNodes);
    Collections.shuffle(an, rd);
    for (P2PNode n : an) {
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

    P2PNode p1 = allNodes.get(pp1);
    P2PNode p2 = allNodes.get(pp2);

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
    for (P2PNode n : allNodes) {
      tot += n.peers.size();
    }
    return (int) (tot / allNodes.size());
  }

  /**
   * A P2P node supports flood by default.
   */
  public class FloodMessage extends Network.Message<P2PNode> {
    private final int size;
    private final int localDelay;
    private final int delayBetweenPeers;

    public FloodMessage(int size, int localDelay, int delayBetweenPeers) {
      this.size = size;
      this.localDelay = localDelay;
      this.delayBetweenPeers = delayBetweenPeers;
    }

    @Override
    public void action(P2PNode from, P2PNode to) {
      if (to.received.add(this)) {
        to.onFlood(from, this);
        List<P2PNode> peers = to.peers.stream().filter(n -> n != from).collect(Collectors.toList());
        if (delayBetweenPeers == 0) {
          send(this, time + 1 + localDelay, to, peers);
        } else {
          int delay = localDelay + 1;
          for (P2PNode n : peers) {
            send(this, time + delay, to, n);
            delay += delayBetweenPeers;
          }
        }
      }
    }

    @Override
    public int size() {
      return size;
    }

    public void kickoff(P2PNode from) {
      from.received.add(this);
      send(this, time + 1, from, from.peers);
    }
  }

}
