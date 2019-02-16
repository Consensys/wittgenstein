package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.FloodMessage;
import net.consensys.wittgenstein.core.messages.StatusFloodMessage;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.server.WParameter;
import java.util.*;
import java.util.function.Predicate;

/**
 * A Protocol that uses p2p flooding to gather data on time needed to find desired node capabilities
 * Idea: To change the node discovery protocol to allow node record sending through gossiping
 */

public class ENRGossiping implements Protocol {
  public final P2PNetwork<ETHNode> network;
  private final ENRParameters params;
  private final NodeBuilder nb;
   final NetworkLatency nl;

  private final int numberOfDifferentCapabilities = 1000;
  private final int numberOfCapabilityPerNode = 10;
  private List<ETHNode> changedNodes;
  public NodeBuilder getNb() {
    return nb;
  }
  public static class ENRParameters extends WParameter {
    /**
     * timeToChange is used to describe the time period, in s, that needs to pass in order to change
     * your capabilities i.e.: when you create new key-value pairs for your record. Only a given
     * percentage of nodes will actually change their capabilities at this time.
     */
    final int timeToChange;

    /**
     * capGossipTime is the period at which every node will regularly gossip their capabilities
     */
    final int capGossipTime;

    /**
     * discardTime is the time after which, if a node(s) hasnt received an update from the nodes it
     * will discard the information it holds about such node(s)
     */
    final int discardTime;

    /**
     * timeToLeave is the time before a node exists the network
     */

    final int timeToLeave;

    /**
     * totalPeers tracks the number of peers a node is connected to
     */
   final int totalPeers;
   final int NODES;
    /**
     * chaningNodes is the % of nodes that regularly change their capabilities
     */
    final float changingNodes;
    final String nodeBuilderName;
    final String networkLatencyName;

    private ENRParameters() {
      this.NODES = 200;
      this.timeToChange = 5000;
      this.capGossipTime = 10000;
      this.discardTime = 100;
      this.timeToLeave = 10000;
      this.totalPeers = 15;
      this.changingNodes = 20;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    private ENRParameters(int timeToChange, int capGossipTime, int discardTime, int timeToLeave,
        int totalPeers, int nodes, float changingNodes, String nodeBuilderName,
        String networkLatencyName) {
      this.NODES = nodes;
      this.timeToChange = timeToChange;
      this.capGossipTime = capGossipTime;
      this.discardTime = discardTime;
      this.timeToLeave = timeToLeave;
      this.totalPeers = totalPeers;
      this.changingNodes = changingNodes;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  private ENRGossiping(ENRParameters params) {
    this.params = params;
    this.network = new P2PNetwork<>(params.totalPeers, true);
    this.nb =  new RegistryNodeBuilders().getByName(params.nodeBuilderName);
    this.nl =  new RegistryNetworkLatencies().getByName(params.networkLatencyName);
  }

  @Override
  public Network<ETHNode> network() {
    return network;
  }

  @Override
  public ENRGossiping copy() {
    return new ENRGossiping(params);
  }

  //Generate new capabilities for new nodes or nodes that periodically change.
  public Map<String, Integer> generateCap() {

    Map<String, Integer> k_v = new HashMap<>();
    while (k_v.size() < numberOfCapabilityPerNode) {
      int cap = network.rd.nextInt(numberOfDifferentCapabilities);
      k_v.put("cap_" + cap, cap);
    }
    return k_v;
  }

  public void selectChangingNodes() {
    int changingCapNodes = (int) (params.totalPeers * params.changingNodes);
    changedNodes = new ArrayList<>(changingCapNodes);
    while (changedNodes.size() < changingCapNodes) {
      changedNodes.add(network.getNodeById(network.rd.nextInt(params.totalPeers)));
    }
  }

  @Override
  public void init() {
    for (int i = 0; i < params.NODES; i++) {
      network.addNode(new ETHNode(network.rd, this.nb, generateCap()));
    }

    network.setPeers();
    selectChangingNodes();
    //A percentage of Nodes change their capabilities every X time as defined by the protocol parameters
    for (ETHNode n : changedNodes) {
      int start = network.rd.nextInt(params.timeToChange) + 1;
      network.registerPeriodicTask(n::changeCap, start, params.timeToChange, n);
    }
    //Nodes broadcast their capabilities every capGossipTime ms with a lag of rand*100 ms
    for (ETHNode n : network.allNodes) {
      int start = network.rd.nextInt(params.capGossipTime) + 1;
      network.registerPeriodicTask(n::findCap, start, params.capGossipTime, n);
    }
    //Send a query for your capabilities matching yours
    //while you haven't found them, or the time hasnt run out you keep querying
    Set<Integer> senders = new HashSet<>(params.totalPeers);

    while (senders.size() < params.NODES) {
      int nodeId = network.rd.nextInt(params.NODES);
      ETHNode n = network.getNodeById(nodeId);
      if (senders.add(nodeId)) {
        n.findCap();
      }
    }
    //Exit the network randomly
    int start = network.rd.nextInt(params.timeToChange);
    ETHNode n = network.getNodeById(0);
    //network.registerPeriodicTask(getPeriodicTask,start,timeToLeave,network.getNodeById(0));

  }

  protected Runnable getPeriodicTask() {
    return () -> {
      int start = network.rd.nextInt(params.timeToChange);
      int nodeId = network.rd.nextInt(params.NODES);
      exitNetwork(network.getNodeById(nodeId));
    };
  }

  //Use .down instead and remove links with other peers
  // assume no messages are sent when it leaves
  // assume that nodes know that the peer has left
  void exitNetwork(ETHNode n) {

  }

  /**
   * The components of a node record are: signature: cryptographic signature of record contents seq:
   * The sequence number, a 64 bit integer. Nodes should increase the number whenever the record
   * changes and republish the record. The remainder of the record consists of arbitrary key/value
   * pairs, which must be sorted by key. A Node sends this message to query for specific
   * capabilities in the network
   */

  public class Record extends StatusFloodMessage<ETHNode> {
    final int seq;
    final Map<String, Integer> k_v;

    Record(int msgId, int size, int localDelay, int delayBetweenPeers, int seq,
        Map<String, Integer> k_v) {
      super(msgId, seq, size, localDelay, delayBetweenPeers);
      this.seq = seq;
      this.k_v = k_v;
    }
  }

  public class ETHNode extends P2PNode<ETHNode> {
    public Map<String, Integer> capabilities;
    private int records = 0;

    ETHNode(Random rd, NodeBuilder nb, Map<String, Integer> capabilities) {
      super(rd, nb);
      this.capabilities = capabilities;
    }

    /**
     * @return true if there is at least one capabilities in common, false otherwise
     */
     boolean matchCap(FloodMessage floodMessage) {
      Record m = (Record) floodMessage;
      return m.k_v.entrySet().stream().allMatch(
          e -> e.getValue().equals(this.capabilities.get(e.getKey())));
    }

    @Override
    public void onFlood(ETHNode from, FloodMessage floodMessage) {
      if (doneAt == 0) {
        if (matchCap(floodMessage)) {
          doneAt = network.time;
          if (!peers.contains(from)) {
            peers.add(from); //Add as peer if your capabilities match
            //Add as peer for sending node
            // createLink
            // add a limit for peers when you reach that value disconnect from peer that has least cap in common
          }
        }
      }
    }

    void findCap() {
      network.send(new Record(nodeId, 1, 10, 10, records++, this.capabilities), this, this.peers);
    }

    void changeCap() {
      capabilities = generateCap();
      network.send(new Record(nodeId, 1, 10, 10, records++, this.capabilities), this, this.peers);
    }

  }

  private void capSearch() {
    Predicate<Protocol> contIf = p1 -> {

      return p1.network().time <= 100000;
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

    ProgressPerTime ppp =
        new ProgressPerTime(this, "", "Nodes that have found capabilities", sg, 1, null);
    ppp.run(contIf);
    /*for(int i=0;i< ppp.protocol.network().allNodes.size();i++){
      List<ETHNode> n = (List<ETHNode>) ppp.protocol.network().allNodes;
      //System.out.println("Node"+ppp.protocol.network().getNodeById(i)+" has "+n.get(i).peers.size()+" peers");
    }*/

  }

  @Override
  public String toString() {
    return "ENRGossiping{" + "timeToChange=" + params.timeToChange + ", capGossipTime=" + params.capGossipTime
        + ", discardTime=" + params.discardTime + ", timeToLeave=" + params.timeToLeave + ", totalPeers="
        + params.totalPeers + ", NODES=" + params.NODES + ", changingNodes=" + params.changingNodes
        + ", numberOfDifferentCapabilities=" + numberOfDifferentCapabilities
        + ", numberOfCapabilityPerNode=" + numberOfCapabilityPerNode + '}';
  }

  public static void main(String... args) {
    new ENRGossiping(new ENRParameters()).capSearch();
  }
}


