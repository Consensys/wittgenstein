package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.FloodMessage;
import net.consensys.wittgenstein.core.messages.StatusFloodMessage;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.server.WParameters;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A Protocol that uses p2p flooding to gather data on time needed to find desired node capabilities
 * Idea: To change the node discovery protocol to allow node record sending through gossiping
 */

public class ENRGossiping implements Protocol {
  public final P2PNetwork<ETHNode> network;
  private final ENRParameters params;
  private final NodeBuilder nb;
  private List<ETHNode> changedNodes;

  public static class ENRParameters extends WParameters {
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
    private final int maxPeers;
    final String nodeBuilderName;
    final String networkLatencyName;
    private final int numberOfDifferentCapabilities;
    private final int capPerNode;

    public ENRParameters() {
      this.NODES = 100;
      this.timeToChange = 1000 * 60 * 60 * 10;
      this.capGossipTime = 1000 * 60 * 5;
      this.discardTime = 100;
      this.timeToLeave = 1000 * 60 * 60 * 5;
      this.totalPeers = 5;
      this.changingNodes = 10;
      this.maxPeers = 50;
      this.numberOfDifferentCapabilities = 15;
      this.capPerNode = 5;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    ENRParameters(int timeToChange, int capGossipTime, int discardTime, int timeToLeave,
        int totalPeers, int nodes, float changingNodes, int maxPeer,
        int numberOfDifferentCapabilities, int capPerNode, String nodeBuilderName,
        String networkLatencyName) {
      this.NODES = nodes;
      this.timeToChange = timeToChange;
      this.capGossipTime = capGossipTime;
      this.discardTime = discardTime;
      this.timeToLeave = timeToLeave;
      this.totalPeers = totalPeers;
      this.changingNodes = changingNodes;
      this.maxPeers = maxPeer;
      this.numberOfDifferentCapabilities = numberOfDifferentCapabilities;
      this.capPerNode = capPerNode;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  ENRGossiping(ENRParameters params) {
    this.params = params;
    this.network = new P2PNetwork<>(params.totalPeers, true);
    this.nb = new RegistryNodeBuilders().getByName(params.nodeBuilderName);
    this.network
        .setNetworkLatency(new RegistryNetworkLatencies().getByName(params.networkLatencyName));
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
  private Set<String> generateCap() {
    Set<String> caps = new HashSet<>();
    while (caps.size() < params.capPerNode) {
      int cap = network.rd.nextInt(params.numberOfDifferentCapabilities);
      caps.add("cap_" + cap);
    }
    return caps;
  }

  private void selectChangingNodes() {
    int changingCapNodes = (int) (params.totalPeers * params.changingNodes);
    changedNodes = new ArrayList<>(changingCapNodes);
    while (changedNodes.size() < changingCapNodes) {
      changedNodes.add(network.getNodeById(network.rd.nextInt(params.totalPeers)));
    }
  }

  private void addNewNode() {
    ETHNode n = new ETHNode(network.rd, this.nb, generateCap());
    network.addNode(n);
    while (n.peers.size() < params.totalPeers) {
      int peerId = network.rd.nextInt(network.allNodes.size());
      if (!network.getNodeById(peerId).down)
        network.createLink(n, network.getNodeById(peerId));
    }
    // All nodes have to leave a day.
    System.out.println("New node: " + n.nodeId + " has " + n.peers.size() + " peers");
    n.start();
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
    Map<String, AtomicInteger> caps = new HashMap<>();
    for (ETHNode n : network.allNodes) {
      for (String s : n.capabilities) {
        AtomicInteger c = caps.computeIfAbsent(s, k -> new AtomicInteger());
        c.incrementAndGet();
      }
    }
    for (Map.Entry<String, AtomicInteger> i : caps.entrySet()) {
      if (i.getValue().get() == 1) {
        throw new IllegalStateException("Capabilities are not well distributed");
      }
      System.out.println(i.getKey() + ": " + i.getValue().get());
    }
    // Divided by 2 to aim for the expected value
    network.registerPeriodicTask(this::addNewNode, 0, params.timeToLeave / 2,
        network.getNodeById(0));
  }

  /**
   * The components of a node record are: signature: cryptographic signature of record contents seq:
   * The sequence number, a 64 bit integer. Nodes should increase the number whenever the record
   * changes and republish the record. The remainder of the record consists of arbitrary key/value
   * pairs, which must be sorted by key. A Node sends this message to query for specific
   * capabilities in the network
   */
  public class Record extends StatusFloodMessage<ETHNode> {
    final ETHNode source;
    final int seq;
    final Set<String> caps;

    Record(ETHNode source, int msgId, int size, int localDelay, int delayBetweenPeers, int seq,
        Set<String> caps) {
      super(msgId, seq, size, localDelay, delayBetweenPeers);
      this.source = source;
      this.seq = seq;
      this.caps = caps;
    }
  }


  public class ETHNode extends P2PNode<ETHNode> {
    Set<String> capabilities;
    private int records = 0;
    public int startTime;


    boolean isFullyConnected() {
      return score(peers) >= capabilities.size();
    }

    /**
     * Calculates the added value of being connected to the node 'p'
     * 
     * @return 0 if no value, > 0 if there is a value, the higher the better
     */
    int addedValue(ETHNode p) {
      int s1 = score(peers);
      List<ETHNode> added = new ArrayList<>(peers);
      added.add(p);
      int s2 = score(added);
      return s2 - s1;
    }

    boolean canConnect(ETHNode p) {
      return !p.down && p.peers.size() < params.maxPeers;
    }

    ETHNode(Random rd, NodeBuilder nb, Set<String> capabilities) {
      super(rd, nb);
      this.capabilities = capabilities;
    }

    @Override
    public void start() {
      startTime = network.time;
      if (isFullyConnected()) {
        doneAt = Math.max(1, network.time);
      }
      // Nodes that join the network after the initial setup will leave, at start network.time =0
      int startExit = Integer.MAX_VALUE;
      if (network.time > 1) {
        // The nodes added at the beginning won't exit the network: this makes
        //  the simulation simpler
        startExit = network.time + network.rd.nextInt(params.timeToLeave);
        network.registerTask(this::exitNetwork, startExit, this);
      }

      //Nodes broadcast their capabilities every capGossipTime ms with a lag of rand*100 ms
      int startBroadcast = network.time + network.rd.nextInt(params.capGossipTime) + 1;
      if (startBroadcast < startExit) {
        // If you're very unlucky you will die before having really started.
        network.registerPeriodicTask(this::broadcastCapabilities, startBroadcast,
            params.capGossipTime, this);
      }
    }

    @Override
    public void onFlood(ETHNode from, FloodMessage floodMessage) {
      Record rc = (Record) floodMessage;
      if (!canConnect(rc.source)) {
        // If we can't connect to this peer there is no need to evaluate anything
        return;
      }
      if (peers.contains(rc.source)) {
        // We're already connected, and we don't support capability changes. We
        // can ignore this message.
        return;
      }
      int addedValue = addedValue(rc.source);

      if (addedValue == 0) {
        // This node has nothing interesting for us.
        return;
      }

      if (peers.size() >= params.maxPeers) {
        if (!removeWorseIfPossible(rc.source)) {
          // We're full and removing a peer won't help.
          return;
        }
      }
      connect(rc.source);
    }

    void connect(ETHNode n) {
      network.createLink(this, n);
      if (doneAt == 0 && isFullyConnected()) {
        doneAt = network.time - startTime;
      }
      if (n.doneAt == 0 && n.isFullyConnected()) {
        n.doneAt = network.time - n.startTime;
      }
    }

    void broadcastCapabilities() {
      network.send(new Record(this, nodeId, 1, 10, 10, records++, this.capabilities), this,
          this.peers);
    }

    void changeCap() {
      capabilities = generateCap();
      network.send(new Record(this, nodeId, 1, 10, 10, records++, this.capabilities), this,
          this.peers);
    }


    /**
     * Count the number of matching capabilities if we're connected to this list of peers.
     */
    int score(List<ETHNode> peers) {
      Set<String> found = new HashSet<>();
      for (ETHNode n : peers) {
        for (String s : n.capabilities) {
          if (capabilities.contains(s)) {
            found.add(s);
          }
        }
      }
      if (found.size() > capabilities.size()) {
        throw new IllegalStateException("found.size() > capabilities.size()");
      }
      return found.size();
    }

    /**
     * Remove the node the least interesting for us considering it would be replaced by
     * 'replacement'. If it's not interesting then don't remove the node
     * 
     * @return true if we removed a node, false otherwise.
     */
    boolean removeWorseIfPossible(ETHNode replacement) {
      ETHNode toRemove = replacement;
      int maxScore = score(peers);
      List<ETHNode> cP = new ArrayList<>(peers);
      for (int i = 0; i < peers.size(); i++) {
        ETHNode cur = cP.get(i);
        cP.set(i, replacement);
        int score = score(cP);
        cP.set(i, cur);
        if (score > maxScore) {
          maxScore = score;
          toRemove = cur;
        }
      }

      if (toRemove != replacement) {
        network.removeLink(this, toRemove);
        return true;
      } else {
        return false;
      }
    }

    void exitNetwork() {
      long live = network.allNodes.stream().filter(n -> !n.down).count();
      if (live <= params.totalPeers) {
        throw new IllegalStateException("We don't have enough peers left, live=" + live
            + ", params.totalPeers=" + params.totalPeers);
      }
      network.disconnect(this);
      network.getNodeById(nodeId).stop();
    }
  }

  private void capSearch() {
    Predicate<Protocol> contIf = p1 -> p1.network().time <= 1000 * 60 * 60 * 10;
    StatsHelper.StatsGetter sg = new StatsHelper.StatsGetter() {
      final List<String> fields = new StatsHelper.SimpleStats(0, 0, 0).fields();

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return StatsHelper.getStatsOn(liveNodes, n -> ((ETHNode) n).doneAt);
      }

    };
    ProgressPerTime ppp =
        new ProgressPerTime(this, "", "Nodes that have found capabilities", sg, 1, null);
    ppp.run(contIf);

  }

  @Override
  public String toString() {
    return "ENRGossiping{" + "timeToChange=" + params.timeToChange + ", capGossipTime="
        + params.capGossipTime + ", discardTime=" + params.discardTime + ", timeToLeave="
        + params.timeToLeave + ", totalPeers=" + params.totalPeers + ", NODES=" + params.NODES
        + ", changingNodes=" + params.changingNodes + ", numberOfDifferentCapabilities="
        + params.numberOfDifferentCapabilities + ", numberOfCapabilityPerNode=" + params.capPerNode
        + '}';
  }

  public static void main(String... args) {
    new ENRGossiping(new ENRParameters()).capSearch();
  }
}


