package net.consensys.wittgenstein.core;

import java.util.ArrayList;
import java.util.stream.Collectors;

public class P2PNetwork extends Network<P2PNode> {
  private final int connectionCount;

  public P2PNetwork(int connectionCount) {
    this.connectionCount = connectionCount;
  }

  public void setPeers() {
    final ArrayList<P2PNode> todo = new ArrayList<>(allNodes);
    int toCreate = (todo.size() * connectionCount) / 2;

    while (toCreate-- > 0) {
      int pp1 = rd.nextInt(todo.size());
      int pp2;
      do {
        pp2 = rd.nextInt(todo.size());
      } while (pp1 == pp2);

      // We could consider that close nodes have a greater probability to be peers, but
      //  today's implementation doesn't have any mechanism for that.
      createLink(todo, pp1, pp2);
    }

    for (P2PNode n : todo) {
      while (n.peers.size() < Math.min(3, this.connectionCount)) {
        int pp2 = rd.nextInt(todo.size());
        if (pp2 != n.nodeId) {
          createLink(todo, n.nodeId, pp2);
        }
      }
    }
  }

  private void createLink(ArrayList<P2PNode> todo, int pp1, int pp2) {
    P2PNode p1 = todo.get(pp1);
    P2PNode p2 = todo.get(pp2);

    p1.peers.add(p2);
    p2.peers.add(p1);
  }

  public class FloodMessage extends Network.Message<P2PNode> {
    private final int size;

    public FloodMessage(int size) {
      this.size = size;
    }

    @Override
    public void action(P2PNode from, P2PNode to) {
      if (to.received.add(this)) {
        to.onFlood(from, this);
        send(this, to, to.peers.stream().filter(n -> n != from).collect(Collectors.toList()));
      }
    }

    @Override
    public int size() {
      return size;
    }
  }

}
