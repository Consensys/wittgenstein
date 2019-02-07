package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.*;
import java.util.function.Predicate;

/**
 * A Protocol that uses p2p flooding to gather data on time needed to find desired node capabilities
 * Idea: To change the node discovery protocol to allow node record sending through gossiping
 */

public class ENRGossiping implements Protocol {
  private final P2PNetwork<ETHNode> network;
  private final NodeBuilder nb;
  private final int timeToChange;
  private final int capGossipTime;
  private final int discardTime;
  private final int timeToLeave;
  private final int totalPeers;
  private final int changingNodes;
  private final List<Map<String, Integer>> records = new ArrayList<>();

  private final int numberOfDifferentCapabilities = 1000;
  private final int numberOfCapabilityPerNode = 10;

  private ENRGossiping(int totalPeers, int timeToChange, int changingNodes, int capGossipTime,
      int discardTime, int timeToLeave, NodeBuilder nb, NetworkLatency nl) {
    this.totalPeers = totalPeers;
    this.timeToChange = timeToChange;
    this.changingNodes = changingNodes;
    this.capGossipTime = capGossipTime;
    this.discardTime = discardTime;
    this.timeToLeave = timeToLeave;
    this.network = new P2PNetwork<>(totalPeers, true);
    this.nb = nb;
    this.network.setNetworkLatency(nl);
  }

  @Override
  public Network<ETHNode> network() {
    return network;
  }

  @Override
  public ENRGossiping copy() {
    return new ENRGossiping(totalPeers, timeToChange, changingNodes, capGossipTime, discardTime,
        timeToLeave, nb, network.networkLatency);
  }

  //Generate new capabilities for new nodes or nodes that periodically change.
  private Map<String, Integer> generateCap(int i) {
    Map<String, Integer> k_v = new HashMap<>();
    k_v.put("id", 4);
    k_v.put(("secp256k1"), i);
    k_v.put("ip", network.rd.nextInt() * 100000);
    k_v.put("tcp", network.rd.nextInt() * 1000);
    k_v.put("udp", network.rd.nextInt() * 1000);

    for (int c=0; c<numberOfCapabilityPerNode; c++) {
      // todo: with this code we can get multiple time the same capabilities
      int cap = network.rd.nextInt(numberOfDifferentCapabilities);
      k_v.put("cap_"+cap, cap);
    }


    return k_v;
  }


  @Override
  public void init() {
    Map<String, Integer> k_v = new HashMap<>();

    for (int i = 0; i < totalPeers; i++) {
      records.add(i, generateCap(i));
      network.addNode(new ETHNode(network.rd, this.nb, records.get(i)));
    }
    network.setPeers();
    //Nodes broadcast their capabilities every 1000 ms with a lag of rand*100 ms
    
    
    for (ETHNode n : network.allNodes) {
      int start = network.rd.nextInt(capGossipTime) + 1;
      network.registerPeriodicTask(n::sendCapabilities, start, capGossipTime, n);
    }


  }

  /**
   * The components of a node record are: signature: cryptographic signature of record contents seq:
   * The sequence number, a 64 bit integer. Nodes should increase the number whenever the record
   * changes and republish the record. The remainder of the record consists of arbitrary key/value
   * pairs, which must be sorted by key. A Node sends this message to query for specific
   * capabilities in the network
   */
  private static class Record extends P2PNetwork.FloodMessage {
    final int seq;
    final Map<String, Integer> k_v;

    Record(int seq, Map<String, Integer> k_v) {
      this.seq = seq;
      this.k_v = k_v;
    }

    @Override
    public void action(ETHNode from, ETHNode to) {
    }
  }


  class ETHNode extends P2PNode<ETHNode> {
    final List<Map<String, Integer>> capabilities = new ArrayList<>();

    public ETHNode(Random rd, NodeBuilder nb, Map<String, Integer> capabilities) {
      super(rd, nb);
      this.capabilities.add(capabilities);
    }

    /**
     * @return true if there is at least one capabilities in common, false otherwise
     */
    private boolean matchCap(P2PNetwork.FloodMessage floodMessage) {
      return false;
    }

    @Override
    protected void onFlood(ETHNode from, P2PNetwork.FloodMessage floodMessage) {
      if (doneAt == 0) {
        if (matchCap(floodMessage)) {
          doneAt = network.time;
        }
      }
    }

    void findCap() {
      network.send(new Record(1, this.capabilities.get(0)), this, this.peers);
    }

    void onLeaving() {
      // network.send(new Records(this.signature,this.k_v), this, this.peers);
    }

    public void sendCapabilities() {
      Record rf = new Record(0, this.capabilities.get(0));
      received.add(rf);
      // Be careful, we never remove messages from p2p flood.
      // You must do more than p2pflood: a message can replace another:
      //  if a node send a message with a newer 'seq', the old message should be
      //  replaced by the new one, and not kept in memory
      network.send(rf, this, peers);
    }

  }

  private static void capSearch() {
    NetworkLatency nl = new NetworkLatency.IC3NetworkLatency();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Random rd = new Random();
    ENRGossiping p = new ENRGossiping(4000, 10, 20, 10, 10, 10, nb, nl);
    Predicate<Protocol> contIf = p1 -> {

      if (p1.network().time > 50000) {
        return false;
      }
      if (p1.network().getNodeById(1).doneAt == 0) {
        return true;
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

    new ProgressPerTime(p, "", "Messages sent", sg, 1, null).run(contIf);
  };

  public static void main(String... args) {
    //capSearch();

  }
}


