package net.consensys.wittgenstein.core;

import java.util.*;

public class P2PNode<TN extends P2PNode> extends Node {
  public final List<TN> peers = new ArrayList<>();
  public Set<P2PNetwork.FloodMessage> received = new HashSet<>();

  public P2PNode(Random rd, NodeBuilder nb) {
    super(rd, nb);
  }

  public P2PNode(Random rd, NodeBuilder nb, boolean byzantine) {
    super(rd, nb, byzantine);
  }

  protected void onFlood(TN from, P2PNetwork.FloodMessage floodMessage) {}
}


