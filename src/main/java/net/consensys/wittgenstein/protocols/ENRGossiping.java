package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.core.messages.StatusFloodMessage;
import net.consensys.wittgenstein.core.messages.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.*;

/**
 * A Protocol that uses p2p flooding to gather data on time needed to find desired node capabilities
 * Idea: To change the node discovery protocol to allow node record sending through gossiping
 */

public class ENRGossiping implements Protocol {
  private final P2PNetwork<ETHNode> network;
  private final NodeBuilder nb;
  /**
   * timeToChange is used to describe the time period, in s, that needs to pass in order to change
   * your capabilities i.e.: when you create new key-value pairs for your record. Only a given
   * percentage of nodes will actually change their capabilities at this time.
   */
  private final int timeToChange;

  /**
   * capGossipTime is the period at which every node will regularly gossip their capabilities
   */
  private final int capGossipTime;

  /**
   * discardTime is the time after which, if a node(s) hasnt received an update from the nodes it
   * will discard the information it holds about such node(s)
   */
  private final int discardTime;

  /**
   * timeToLeave is the time before a node exists the network
   */

  private final int timeToLeave;

  /**
   * totalPeers tracks the number of peers a node is connected to
   */
  private final int totalPeers;
  private final int NODES;
  /**
   * chaningNodes is the % of nodes that regularly change their capabilities
   */
  private final int changingNodes;

  private final int numberOfDifferentCapabilities = 1000;
  private final int numberOfCapabilityPerNode = 10;

  ENRGossiping(int NODES, int totalPeers, int timeToChange, int changingNodes, int capGossipTime,
      int discardTime, int timeToLeave) {
    this.NODES = NODES;
    this.totalPeers = totalPeers;
    this.timeToChange = timeToChange;
    this.changingNodes = changingNodes;
    this.capGossipTime = capGossipTime;
    this.discardTime = discardTime;
    this.timeToLeave = timeToLeave;
    this.network = new P2PNetwork<>(totalPeers, true);
    this.nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    this.network.setNetworkLatency(new NetworkLatency.IC3NetworkLatency());
  }

  @Override
  public Network<ETHNode> network() {
    return network;
  }

  @Override
  public ENRGossiping copy() {
    return new ENRGossiping(NODES, totalPeers, timeToChange, changingNodes, capGossipTime,
        discardTime, timeToLeave);
  }

  //Generate new capabilities for new nodes or nodes that periodically change.
  private Map<String, Integer> generateCap() {

    Map<String, Integer> k_v = new HashMap<>();
    while (k_v.size() < numberOfCapabilityPerNode) {
      int cap = network.rd.nextInt(numberOfDifferentCapabilities);
      k_v.put("cap_" + cap, cap);
    }
    return k_v;
  }


  @Override
  public void init() {
    for (int i = 0; i < NODES; i++) {
      network.addNode(new ETHNode(network.rd, this.nb, generateCap()));
    }
    network.setPeers();
    //Nodes broadcast their capabilities every capGossipTime ms with a lag of rand*100 ms

    for (ETHNode n : network.allNodes) {
      int start = network.rd.nextInt(capGossipTime) + 1;
      network.registerPeriodicTask(n::findCap, start, capGossipTime, n);
    }

    //Send a query for your capabilities matching yours
    //while you haven't found them, or the time hasnt run out you keep querying
    Set<Integer> senders = new HashSet<>(totalPeers);

    while (senders.size() < NODES) {
      int nodeId = network.rd.nextInt(NODES);
      ETHNode n = network.getNodeById(nodeId);
      if (senders.add(nodeId)) {
        n.findCap();
      }
    }
  }

  /**
   * The components of a node record are: signature: cryptographic signature of record contents seq:
   * The sequence number, a 64 bit integer. Nodes should increase the number whenever the record
   * changes and republish the record. The remainder of the record consists of arbitrary key/value
   * pairs, which must be sorted by key. A Node sends this message to query for specific
   * capabilities in the network
   */

  private class Record extends StatusFloodMessage {
    final int seq;
    final Map<String, Integer> k_v;

    Record(int msgId, int size, int localDelay, int delayBetweenPeers, int seq,
        Map<String, Integer> k_v) {
      super(msgId, seq, size, localDelay, delayBetweenPeers);
      this.seq = seq;
      this.k_v = k_v;
    }
  }

  class ETHNode extends P2PNode<ETHNode> {
    final Map<String, Integer> capabilities;
    private int records = 0;

    private ETHNode(Random rd, NodeBuilder nb, Map<String, Integer> capabilities) {
      super(rd, nb);
      this.capabilities = capabilities;
    }

    /**
     * @return true if there is at least one capabilities in common, false otherwise
     */
    private boolean matchCap(FloodMessage floodMessage) {
      //check with filter() if doenst work
      Record m = (Record) floodMessage;
      return m.k_v.entrySet().stream().allMatch(
          e -> e.getValue().equals(this.capabilities.get(e.getKey())));
    }

    @Override
    public void onFlood(ETHNode from, FloodMessage floodMessage) {
      if (doneAt == 0) {
        if (matchCap(floodMessage)) {
          doneAt = network.time;
        }
      }
    }

    void findCap() {
      network.send(new Record(nodeId, 1, 10, 10, records++, this.capabilities), this, this.peers);
    }

  }

  private void capSearch() {
    Predicate<Protocol> contIf = p1 -> {

      if (p1.network().time > 50000) {
        return false;
      }
      for (Node n : p1.network().allNodes) {
        if (n.getDoneAt() == 0) {
          return true;
        }
      }
      return false;
    };

    StatsHelper.StatsGetter sg = new StatsHelper.StatsGetter() {

      final List<String> fields = new StatsHelper.Counter(0).fields();

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return new StatsHelper.Counter(liveNodes.stream().filter(n -> n.getDoneAt() > 0).count());
      }
    };

    new ProgressPerTime(this, "", "Nodes that have found capabilities", sg, 1, null).run(contIf);
  }

  @Override
  public String toString() {
    return "ENRGossiping{" + "timeToChange=" + timeToChange + ", capGossipTime=" + capGossipTime
        + ", discardTime=" + discardTime + ", timeToLeave=" + timeToLeave + ", totalPeers="
        + totalPeers + ", NODES=" + NODES + ", changingNodes=" + changingNodes
        + ", numberOfDifferentCapabilities=" + numberOfDifferentCapabilities
        + ", numberOfCapabilityPerNode=" + numberOfCapabilityPerNode + '}';
  }

  public static void main(String... args) {
    new ENRGossiping(200, 15, 500, 20, 10000, 10, 10000).capSearch();

  }
}


