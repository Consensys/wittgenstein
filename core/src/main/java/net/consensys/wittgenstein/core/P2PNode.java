package net.consensys.wittgenstein.core;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;
import net.consensys.wittgenstein.core.messages.FloodMessage;

public class P2PNode<TN extends P2PNode> extends Node {

  @JsonSerialize(converter = ListNodeConverter.class)
  public final List<TN> peers = new ArrayList<>();

  protected Map<Long, Set<FloodMessage>> received = new HashMap<>();

  public Set<FloodMessage> getMsgReceived(long id) {
    return received.computeIfAbsent(id, k -> new HashSet<>());
  }

  public P2PNode(Random rd, NodeBuilder nb) {
    this(rd, nb, false);
  }

  public P2PNode(Random rd, NodeBuilder nb, boolean byzantine) {
    super(rd, nb, byzantine);
  }

  public void onFlood(TN from, FloodMessage floodMessage) {}
}
