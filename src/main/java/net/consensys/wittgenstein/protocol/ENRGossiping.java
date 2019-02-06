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
  final static Random rd= new Random();

  private ENRGossiping(int totalPeers, int timeToChange, int capGossipTime, int discardTime,
      int timeToLeave, NodeBuilder nb, NetworkLatency nl) {
    this.totalPeers = totalPeers;
    this.timeToChange = timeToChange;
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
    return new ENRGossiping(totalPeers, timeToChange, capGossipTime, discardTime, timeToLeave, nb,
        network.networkLatency);
  }

  //Generate new capabilities for new nodes or nodes that periodically change.
  private static Map<Byte, Integer> generateCap(int i) {
      Random rd = new Random();
    Map<Byte, Integer> k_v = new HashMap<Byte, Integer>();
    k_v.put(Byte.valueOf("id"), 4);
    k_v.put(Byte.valueOf("secp256k1"), i);
    k_v.put(Byte.valueOf("ip"), rd.nextInt()*100000);
    k_v.put(Byte.valueOf("tcp"), rd.nextInt()*1000);
    k_v.put(Byte.valueOf("udp"), rd.nextInt()*1000);
    return k_v;
  }

    private static Map<Byte, Integer> generateCap(int i, List<Byte> keys) {
        Random rd = new Random();
        Map<Byte, Integer> k_v = new HashMap<Byte, Integer>();
       for(Byte b: keys){
           k_v.put(b,rd.nextInt()*1000);
       }
        return k_v;
    }


    @Override
  public void init() {
      Map<Byte, Integer> k_v = new HashMap<Byte, Integer>();

    for (int i = 0; i < totalPeers; i++) {
        if(i == 1){

        }
      network.addNode(new ETHNode(network.rd, this.nb, generateCap(i)));
    }
    network.setPeers();
    ETHNode n = network.getNodeById(1);
    n.findCap();
  }

  /**
   * The components of a node record are: signature: cryptographic signature of record contents seq:
   * The sequence number, a 64 bit integer. Nodes should increase the number whenever the record
   * changes and republish the record. The remainder of the record consists of arbitrary key/value
   * pairs, which must be sorted by key. A Node sends this message to query for specific
   * capabilities in the network
   */
  private static class FindRecord extends P2PNetwork.Message<ETHNode> {
    int seq;
    Map<Byte, Integer> k_v;

    FindRecord(int seq, Map<Byte, Integer> k_v) {
      this.seq = seq;
      this.k_v = k_v;
    }

    @Override
    public void action(ETHNode from, ETHNode to) {
      from.findCap();
    }
  }
  //When capabilities are found,before exiting the network the node sends it's capabilities through gossiping
  private static class RecordFound extends P2PNetwork.Message<ETHNode> {

    @Override
    public void action(ETHNode from, ETHNode to) {
      from.onLeaving();
    }
  }

  class ETHNode extends P2PNode<ETHNode> {
    int nodeId;
    boolean capFound;
    final Map<Byte, Integer> capabilities;

    public ETHNode(Random rd, NodeBuilder nb, Map<Byte, Integer> capabilities) {
      super(rd, nb);
      this.capabilities = capabilities;

    }

    @Override
    protected void onFlood(ETHNode from, P2PNetwork.FloodMessage floodMessage) {
        capFound = this.capabilities.equals(from.capabilities);
      if (capFound) {
        doneAt = network.time;
      }
    }
    void findCap(){
        network.send(new FindRecord(1,this.capabilities),this,this.peers);
    }

    void onLeaving() {
      // network.send(new Records(this.signature,this.k_v), this, this.peers);
    }

    void atCapChange() {
      // network.send(new Records(this.signature,this.k_v), this, this.peers);
    }

  }

  private static void capSearch() {
    NetworkLatency nl = new NetworkLatency.IC3NetworkLatency();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Random rd = new Random();
    ENRGossiping p = new ENRGossiping(4000, 10, 10, 10, 10, nb, nl);
      Predicate<Protocol> contIf = p1 -> {

          if (p1.network().time > 50000) {
              return false;
          }
          if(p1.network().getNodeById(1).doneAt == 0){
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

          new ProgressPerTime(p,"","Messages sent",sg,1,null).run(contIf);
      };

    public static void main(String... args){
        capSearch();
    }
  }


