package net.consensys.wittgenstein.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class P2PNode extends Node {
  public final List<P2PNode> peers = new ArrayList<>();
  public Set<P2PNetwork.FloodMessage> received = new HashSet<>();

  public P2PNode(NodeBuilder nb) {
    super(nb);
  }

  public P2PNode(NodeBuilder nb, boolean byzantine) {
    super(nb, byzantine);
  }

  protected void onFlood(P2PNode from, P2PNetwork.FloodMessage floodMessage) {}
}
