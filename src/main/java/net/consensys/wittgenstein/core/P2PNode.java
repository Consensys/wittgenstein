package net.consensys.wittgenstein.core;

import java.util.*;

public class P2PNode<TN extends P2PNode> extends Node {
  public final List<TN> peers = new ArrayList<>();
  public Map<Long, Set<P2PNetwork.FloodMessage>> received = new HashMap<>();

  public Set<P2PNetwork.FloodMessage> getSet(long id) {
    return received.computeIfAbsent(id, k -> new HashSet<>());
  }

  public P2PNode(Random rd, NodeBuilder nb) {
    this(rd, nb, false);
  }

  public P2PNode(Random rd, NodeBuilder nb, boolean byzantine) {
    super(rd, nb, byzantine);
  }

  protected void onFlood(TN from, P2PNetwork.FloodMessage floodMessage) {}
}


