package net.consensys.wittgenstein.core;

import java.util.stream.Collectors;

public class P2PNetwork extends Network<P2PNode> {
  private final int connectionCount;

  public P2PNetwork(int connectionCount) {
    this.connectionCount = connectionCount;
  }

  public void setPeers() {
    int toCreate = (allNodes.size() * connectionCount) / 2;

    while (toCreate-- > 0) {
      int pp1 = rd.nextInt(allNodes.size());
      int pp2;
      do {
        pp2 = rd.nextInt(allNodes.size());
      } while (pp1 == pp2);

      // We could consider that close nodes have a greater probability to be peers, but
      //  today's implementation doesn't have any mechanism for that.
      createLink(pp1, pp2);
    }

    for (P2PNode n : allNodes) {
      while (n.peers.size() < Math.min(3, this.connectionCount)) {
        int pp2 = rd.nextInt(allNodes.size());
        if (pp2 != n.nodeId) {
          createLink(n.nodeId, pp2);
        }
      }
    }
  }

  private void createLink(int pp1, int pp2) {
    if (pp1 == pp2) {
      throw new IllegalStateException();
    }

    P2PNode p1 = allNodes.get(pp1);
    P2PNode p2 = allNodes.get(pp2);

    p1.peers.add(p2);
    p2.peers.add(p1);
  }

  /**
   * A P2P node supports flood by default.
   */
  public class FloodMessage extends Network.Message<P2PNode> {
    private final int size;
    private final int localDelay;

    public FloodMessage(int size, int localDelay) {
      this.size = size;
      this.localDelay = localDelay;
    }

    @Override
    public void action(P2PNode from, P2PNode to) {
      if (to.received.add(this)) {
        to.onFlood(from, this);
        send(this, time + 1 + localDelay, to,
            to.peers.stream().filter(n -> n != from).collect(Collectors.toList()));
      }
    }

    @Override
    public int size() {
      return size;
    }

    public void kickoff(P2PNode from) {
      from.received.add(this);
      send(this, time + 1 + localDelay, from, from.peers);
    }
  }

}
