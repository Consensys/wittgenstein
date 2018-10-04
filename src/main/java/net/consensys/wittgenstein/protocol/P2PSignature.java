package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import java.util.*;

/**
 * A p2p protocol for BLS signature aggregation.
 * <p>
 * A node: Sends its states to all its direct peers whenever it changes Keeps the list of the states
 * of its direct peers Sends, every x milliseconds, to one of its peers a set of missing signatures
 * Runs in parallel a task to validate the signatures sets it has received.
 */
@SuppressWarnings("WeakerAccess")
public class P2PSignature {
  /**
   * The nuumber of nodes in the network
   */
  final int nodeCount;

  /**
   * The number of signatures to reach to finish the protocol.
   */
  final int threshold;

  /**
   * The typical number of peers a peer has. It can be less (but at least 3) or more.
   */
  final int connectionCount;

  /**
   * The time it takes to do a pairing for a node.
   */
  final int pairingTime;

  /**
   * The protocol sends a set of sigs every 'sigsSendPeriod' milliseconds
   */
  final int sigsSendPeriod;

  /**
   * @see P2PSigNode#sendSigs for the two strategies.
   */
  final boolean doubleAggregateStrategy;

  final P2PNetwork network;
  final Node.NodeBuilder nb;

  public P2PSignature(int nodeCount, int threshold, int connectionCount, int pairingTime,
      int sigsSendPeriod, boolean doubleAggregateStrategy) {
    this.nodeCount = nodeCount;
    this.threshold = threshold;
    this.connectionCount = connectionCount;
    this.pairingTime = pairingTime;
    this.sigsSendPeriod = sigsSendPeriod;
    this.doubleAggregateStrategy = doubleAggregateStrategy;

    this.network = new P2PNetwork(connectionCount);
    this.nb = new Node.NodeBuilderWithRandomPosition(network.rd);
  }

  static class State extends Network.Message<P2PSigNode> {
    final BitSet desc;
    final P2PSigNode who;

    public State(P2PSigNode who) {
      this.desc = (BitSet) who.verifiedSignatures.clone();
      this.who = who;
    }

    @Override
    public int size() {
      return desc.size() / 8;
    }

    @Override
    public void action(P2PSigNode from, P2PSigNode to) {
      to.onPeerState(this);
    }
  }

  static class SendSigs extends Network.Message<P2PSigNode> {
    final BitSet sigs;

    public SendSigs(BitSet sigs) {
      this.sigs = sigs;
    }

    @Override
    public int size() {
      return sigs.cardinality() * 48;
    }

    @Override
    public void action(P2PSigNode from, P2PSigNode to) {
      to.onNewSig(sigs);
    }
  }

  public class P2PSigNode extends P2PNode {
    final BitSet verifiedSignatures = new BitSet(nodeCount);
    final Set<BitSet> toVerify = new HashSet<>();
    final Map<Integer, State> peersState = new HashMap<>();

    boolean done = false;
    long doneAt = 0;

    P2PSigNode() {
      super(nb);
      verifiedSignatures.set(nodeId, true);
    }

    /**
     * Asynchronous, so when we receive a state it can be an old one.
     */
    void onPeerState(State state) {
      int newCard = state.desc.cardinality();
      State old = peersState.get(state.who.nodeId);

      if (newCard < threshold && (old == null || old.desc.cardinality() < newCard)) {
        peersState.put(state.who.nodeId, state);
      }
    }

    /**
     * If the state has changed we send a message to all. If we're done, we updates all our peers.
     */
    void updateVerifiedSignatures(BitSet sigs) {
      int oldCard = verifiedSignatures.cardinality();
      verifiedSignatures.or(sigs);
      int newCard = verifiedSignatures.cardinality();

      if (newCard > oldCard) {
        sendStateToPeers();

        if (!done && verifiedSignatures.cardinality() >= threshold) {
          doneAt = network.time;
          done = true;
          while (!peersState.isEmpty()) {
            sendSigs();
          }
        }
      }
    }

    void sendStateToPeers() {
      State s = new State(this);
      network.send(s, this, peers);
    }

    /**
     * Nothing much to do when we receive a sig set: we just add it to our toVerify list.
     */
    void onNewSig(BitSet sigs) {
      toVerify.add(sigs);
    }

    /**
     * We select a peer which needs some signatures we have. We also remove it from out list once we
     * sent it a signature set.
     */
    void sendSigs() {
      State found = null;
      BitSet toSend = null;
      Iterator<State> it = peersState.values().iterator();
      while (it.hasNext() && found == null) {
        State cur = it.next();
        toSend = (BitSet) cur.desc.clone();
        toSend.flip(0, nodeCount);
        toSend.and(verifiedSignatures);
        int v1 = toSend.cardinality();

        if (v1 > 0) {
          found = cur;
          it.remove();
        }
      }

      if (found != null) {
        SendSigs ss = new SendSigs(toSend);
        network.send(ss, delayToSend(ss.sigs), this, Collections.singleton(found.who));
      }
    }

    /**
     * We add a small delay to take into account the message size. This should likely be moved to
     * the framework.
     */
    int delayToSend(BitSet sigs) {
      return network.time + 1 + sigs.cardinality() / 100;
    }


    public void checkSigs() {
      if (doubleAggregateStrategy) {
        checkSigs2();
      } else {
        checkSigs1();
      }
    }


    /**
     * Strategy 1: we select the set of signatures which contains the most new signatures. As we
     * send a message to all our peers each time our state change we send more messages with this
     * strategy.
     */
    protected void checkSigs1() {
      BitSet best = null;
      int bestV = 0;
      Iterator<BitSet> it = toVerify.iterator();
      while (it.hasNext()) {
        BitSet o1 = it.next();
        BitSet oo1 = ((BitSet) o1.clone());
        oo1.andNot(verifiedSignatures);
        int v1 = oo1.cardinality();

        if (v1 == 0) {
          it.remove();
        } else {
          if (v1 > bestV) {
            bestV = v1;
            best = o1;
          }
        }
      }

      if (best != null) {
        toVerify.remove(best);
        final BitSet tBest = best;
        network.registerTask(() -> P2PSigNode.this.updateVerifiedSignatures(tBest),
            network.time + pairingTime * 2, P2PSigNode.this);
      }
    }

    /**
     * Strategy 2: we aggregate all signatures together
     */
    protected void checkSigs2() {
      BitSet agg = null;
      for (BitSet o1 : toVerify) {
        if (agg == null) {
          agg = o1;
        } else {
          agg.or(o1);
        }
      }
      toVerify.clear();

      if (agg != null) {
        BitSet oo1 = ((BitSet) agg.clone());
        oo1.andNot(verifiedSignatures);

        if (oo1.cardinality() > 0) {
          // There is at least one signature we don't have yet
          final BitSet tBest = agg;
          network.registerTask(() -> P2PSigNode.this.updateVerifiedSignatures(tBest),
              network.time + pairingTime * 2, P2PSigNode.this);
        }
      }
    }


    @Override
    public String toString() {
      return "P2PSigNode{" + "nodeId=" + nodeId + ", doneAt=" + doneAt + ", sigs="
          + verifiedSignatures.cardinality() + ", msgReceived=" + msgReceived + ", msgSent="
          + msgSent + ", KBytesSent=" + bytesSent / 1024 + ", KBytesReceived="
          + bytesReceived / 1024 + '}';
    }
  }

  P2PSigNode init() {
    P2PSigNode last = null;
    for (int i = 0; i < nodeCount; i++) {
      final P2PSigNode n = new P2PSigNode();
      last = n;
      network.addNode(n);
      network.registerTask(n::sendStateToPeers, 1, n);
      network.registerConditionalTask(n::sendSigs, 1, sigsSendPeriod, n,
          () -> !(n.peersState.isEmpty()), () -> !n.done);
      network.registerConditionalTask(n::checkSigs, 1, pairingTime, n, () -> !n.toVerify.isEmpty(),
          () -> !n.done);
    }

    network.setPeers();

    return last;
  }

  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    System.out.println("" + nl);

    boolean printLat = false;
    for (int cnt = 8; cnt < 25; cnt += 5) {
      for (int sendPeriod = 20; sendPeriod < 100; sendPeriod += 20) {
        for (int nodeCt = 500; nodeCt < 15100; nodeCt += 500) {
          P2PSignature p2ps = new P2PSignature(nodeCt, nodeCt / 2 + 1, cnt, 3, sendPeriod, true);
          p2ps.network.setNetworkLatency(nl);
          P2PSigNode observer = p2ps.init();

          if (!printLat) {
            System.out.println("NON P2P " + NetworkLatency.estimateLatency(p2ps.network, 100000));
            System.out.println("\nP2P " + NetworkLatency.estimateP2PLatency(p2ps.network, 100000));
            printLat = true;
          }

          p2ps.network.run(5);
          System.out.println("cnt=" + cnt + ", sendPeriod=" + sendPeriod + " " + observer);
        }
      }
    }
  }

}

/*
NetworkLatencyByDistance{fix=10, max=200, spread=50%}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=499, doneAt=550, sigs=405, msgReceived=133, msgSent=145, KBytesSent=148, KBytesReceived=161}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=999, doneAt=637, sigs=515, msgReceived=228, msgSent=69, KBytesSent=210, KBytesReceived=221}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=1499, doneAt=663, sigs=953, msgReceived=153, msgSent=146, KBytesSent=448, KBytesReceived=649}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=1999, doneAt=689, sigs=1295, msgReceived=438, msgSent=142, KBytesSent=1173, KBytesReceived=1281}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=2499, doneAt=793, sigs=1402, msgReceived=289, msgSent=478, KBytesSent=1212, KBytesReceived=908}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=2999, doneAt=818, sigs=1548, msgReceived=249, msgSent=236, KBytesSent=1169, KBytesReceived=1450}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=3499, doneAt=749, sigs=2267, msgReceived=740, msgSent=517, KBytesSent=3185, KBytesReceived=3126}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=3999, doneAt=812, sigs=2118, msgReceived=364, msgSent=143, KBytesSent=1498, KBytesReceived=2433}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=4499, doneAt=873, sigs=3372, msgReceived=309, msgSent=345, KBytesSent=1665, KBytesReceived=2012}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=4999, doneAt=851, sigs=2675, msgReceived=546, msgSent=917, KBytesSent=3652, KBytesReceived=3131}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=5499, doneAt=958, sigs=2757, msgReceived=540, msgSent=188, KBytesSent=2787, KBytesReceived=3836}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=5999, doneAt=968, sigs=4525, msgReceived=676, msgSent=201, KBytesSent=2893, KBytesReceived=3745}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=6499, doneAt=955, sigs=5176, msgReceived=806, msgSent=1757, KBytesSent=7032, KBytesReceived=4703}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=6999, doneAt=950, sigs=3574, msgReceived=472, msgSent=213, KBytesSent=3717, KBytesReceived=4724}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=7499, doneAt=985, sigs=3894, msgReceived=509, msgSent=316, KBytesSent=3458, KBytesReceived=3615}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=7999, doneAt=940, sigs=4014, msgReceived=739, msgSent=167, KBytesSent=3740, KBytesReceived=5843}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=8499, doneAt=965, sigs=4280, msgReceived=468, msgSent=382, KBytesSent=4867, KBytesReceived=5568}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=8999, doneAt=1066, sigs=4594, msgReceived=306, msgSent=483, KBytesSent=4166, KBytesReceived=3781}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=9499, doneAt=1046, sigs=4978, msgReceived=767, msgSent=183, KBytesSent=4880, KBytesReceived=6675}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=9999, doneAt=1148, sigs=5393, msgReceived=556, msgSent=315, KBytesSent=6234, KBytesReceived=7251}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=10499, doneAt=1131, sigs=6445, msgReceived=510, msgSent=826, KBytesSent=5317, KBytesReceived=3847}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=10999, doneAt=1122, sigs=6018, msgReceived=530, msgSent=363, KBytesSent=8142, KBytesReceived=8441}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=11499, doneAt=1097, sigs=5997, msgReceived=436, msgSent=206, KBytesSent=4592, KBytesReceived=5420}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=11999, doneAt=1189, sigs=6232, msgReceived=370, msgSent=84, KBytesSent=4978, KBytesReceived=6007}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=12499, doneAt=691, sigs=6286, msgReceived=655, msgSent=453, KBytesSent=9090, KBytesReceived=8967}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=12999, doneAt=1164, sigs=6690, msgReceived=503, msgSent=580, KBytesSent=7293, KBytesReceived=6295}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=13499, doneAt=1292, sigs=8084, msgReceived=725, msgSent=139, KBytesSent=5744, KBytesReceived=8339}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=13999, doneAt=1280, sigs=7610, msgReceived=428, msgSent=250, KBytesSent=5803, KBytesReceived=7022}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=14499, doneAt=1244, sigs=8481, msgReceived=519, msgSent=1216, KBytesSent=10405, KBytesReceived=6773}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=14999, doneAt=1372, sigs=7768, msgReceived=614, msgSent=388, KBytesSent=8070, KBytesReceived=8907}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=499, doneAt=667, sigs=259, msgReceived=104, msgSent=100, KBytesSent=140, KBytesReceived=143}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=999, doneAt=871, sigs=622, msgReceived=173, msgSent=80, KBytesSent=248, KBytesReceived=308}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=1499, doneAt=823, sigs=777, msgReceived=119, msgSent=100, KBytesSent=412, KBytesReceived=657}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=1999, doneAt=876, sigs=1016, msgReceived=357, msgSent=153, KBytesSent=998, KBytesReceived=1222}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=2499, doneAt=1000, sigs=1311, msgReceived=225, msgSent=328, KBytesSent=1025, KBytesReceived=1076}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=2999, doneAt=1057, sigs=1642, msgReceived=199, msgSent=152, KBytesSent=1323, KBytesReceived=1441}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=3499, doneAt=959, sigs=1919, msgReceived=640, msgSent=444, KBytesSent=2762, KBytesReceived=2995}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=3999, doneAt=1137, sigs=2044, msgReceived=305, msgSent=115, KBytesSent=2017, KBytesReceived=2349}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=4499, doneAt=1135, sigs=2374, msgReceived=253, msgSent=235, KBytesSent=1757, KBytesReceived=1848}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=4999, doneAt=1150, sigs=2749, msgReceived=407, msgSent=528, KBytesSent=3400, KBytesReceived=2901}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=5499, doneAt=1330, sigs=2805, msgReceived=501, msgSent=175, KBytesSent=2854, KBytesReceived=3957}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=5999, doneAt=1289, sigs=3099, msgReceived=569, msgSent=229, KBytesSent=3107, KBytesReceived=3439}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=6499, doneAt=1162, sigs=3660, msgReceived=567, msgSent=1228, KBytesSent=5889, KBytesReceived=5106}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=6999, doneAt=1380, sigs=3559, msgReceived=390, msgSent=178, KBytesSent=4038, KBytesReceived=4295}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=7499, doneAt=1400, sigs=3780, msgReceived=437, msgSent=250, KBytesSent=3159, KBytesReceived=3651}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=7999, doneAt=1354, sigs=4884, msgReceived=643, msgSent=156, KBytesSent=4879, KBytesReceived=5668}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=8499, doneAt=1354, sigs=4283, msgReceived=379, msgSent=267, KBytesSent=5191, KBytesReceived=5809}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=8999, doneAt=1502, sigs=4714, msgReceived=249, msgSent=432, KBytesSent=4187, KBytesReceived=3833}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=9499, doneAt=1508, sigs=4841, msgReceived=639, msgSent=169, KBytesSent=4912, KBytesReceived=6120}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=9999, doneAt=1492, sigs=5107, msgReceived=468, msgSent=267, KBytesSent=5439, KBytesReceived=7000}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=10499, doneAt=1430, sigs=8005, msgReceived=432, msgSent=763, KBytesSent=4429, KBytesReceived=3571}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=10999, doneAt=1527, sigs=5674, msgReceived=404, msgSent=232, KBytesSent=7065, KBytesReceived=7727}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=11499, doneAt=1466, sigs=5784, msgReceived=366, msgSent=172, KBytesSent=4694, KBytesReceived=5092}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=11999, doneAt=1611, sigs=6869, msgReceived=353, msgSent=82, KBytesSent=5579, KBytesReceived=5916}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=12499, doneAt=985, sigs=6379, msgReceived=738, msgSent=264, KBytesSent=8956, KBytesReceived=10117}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=12999, doneAt=1747, sigs=6823, msgReceived=412, msgSent=547, KBytesSent=7343, KBytesReceived=6650}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=13499, doneAt=1713, sigs=7262, msgReceived=594, msgSent=100, KBytesSent=6042, KBytesReceived=7813}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=13999, doneAt=1763, sigs=7111, msgReceived=397, msgSent=272, KBytesSent=6596, KBytesReceived=6742}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=14499, doneAt=1625, sigs=8989, msgReceived=409, msgSent=985, KBytesSent=10457, KBytesReceived=7434}
cnt=10, sendPeriod=40 P2PSigNode{nodeId=14999, doneAt=1887, sigs=7868, msgReceived=525, msgSent=276, KBytesSent=8302, KBytesReceived=9025}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=499, doneAt=742, sigs=257, msgReceived=91, msgSent=96, KBytesSent=136, KBytesReceived=152}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=999, doneAt=938, sigs=520, msgReceived=169, msgSent=73, KBytesSent=175, KBytesReceived=284}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=1499, doneAt=1034, sigs=775, msgReceived=114, msgSent=83, KBytesSent=552, KBytesReceived=667}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=1999, doneAt=929, sigs=1010, msgReceived=307, msgSent=125, KBytesSent=998, KBytesReceived=1132}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=2499, doneAt=1265, sigs=1311, msgReceived=227, msgSent=309, KBytesSent=1070, KBytesReceived=963}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=2999, doneAt=1339, sigs=1525, msgReceived=186, msgSent=108, KBytesSent=1062, KBytesReceived=1467}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=3499, doneAt=1101, sigs=1928, msgReceived=561, msgSent=318, KBytesSent=2663, KBytesReceived=2849}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=3999, doneAt=1323, sigs=2128, msgReceived=279, msgSent=109, KBytesSent=2099, KBytesReceived=2325}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=4499, doneAt=1279, sigs=2287, msgReceived=215, msgSent=165, KBytesSent=1761, KBytesReceived=1786}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=4999, doneAt=1383, sigs=3873, msgReceived=395, msgSent=548, KBytesSent=3344, KBytesReceived=3111}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=5499, doneAt=1591, sigs=3222, msgReceived=463, msgSent=194, KBytesSent=3612, KBytesReceived=3801}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=5999, doneAt=1360, sigs=3433, msgReceived=507, msgSent=199, KBytesSent=3251, KBytesReceived=3627}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=6499, doneAt=1473, sigs=3325, msgReceived=499, msgSent=1074, KBytesSent=5301, KBytesReceived=4737}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=6999, doneAt=1541, sigs=3561, msgReceived=356, msgSent=120, KBytesSent=3708, KBytesReceived=4460}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=7499, doneAt=1698, sigs=3899, msgReceived=409, msgSent=280, KBytesSent=3440, KBytesReceived=3641}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=7999, doneAt=1704, sigs=4013, msgReceived=589, msgSent=150, KBytesSent=3919, KBytesReceived=5416}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=8499, doneAt=1629, sigs=4505, msgReceived=354, msgSent=208, KBytesSent=5402, KBytesReceived=5476}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=8999, doneAt=1732, sigs=4854, msgReceived=229, msgSent=369, KBytesSent=4003, KBytesReceived=3838}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=9499, doneAt=1740, sigs=4956, msgReceived=597, msgSent=161, KBytesSent=5124, KBytesReceived=6002}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=9999, doneAt=1913, sigs=5043, msgReceived=461, msgSent=317, KBytesSent=6181, KBytesReceived=6857}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=10499, doneAt=1728, sigs=5570, msgReceived=386, msgSent=631, KBytesSent=4428, KBytesReceived=3659}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=10999, doneAt=1836, sigs=6032, msgReceived=376, msgSent=225, KBytesSent=7528, KBytesReceived=8239}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=11499, doneAt=1784, sigs=5920, msgReceived=350, msgSent=183, KBytesSent=4843, KBytesReceived=4970}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=11999, doneAt=1855, sigs=6017, msgReceived=319, msgSent=103, KBytesSent=5056, KBytesReceived=5450}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=12499, doneAt=1353, sigs=6271, msgReceived=871, msgSent=292, KBytesSent=8832, KBytesReceived=9841}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=12999, doneAt=2105, sigs=7566, msgReceived=385, msgSent=412, KBytesSent=7576, KBytesReceived=7260}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=13499, doneAt=1974, sigs=7101, msgReceived=560, msgSent=93, KBytesSent=5970, KBytesReceived=8059}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=13999, doneAt=1972, sigs=7027, msgReceived=370, msgSent=184, KBytesSent=6084, KBytesReceived=7144}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=14499, doneAt=1945, sigs=8628, msgReceived=363, msgSent=1040, KBytesSent=10214, KBytesReceived=7260}
cnt=10, sendPeriod=60 P2PSigNode{nodeId=14999, doneAt=2344, sigs=7606, msgReceived=501, msgSent=245, KBytesSent=8028, KBytesReceived=9065}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=499, doneAt=801, sigs=270, msgReceived=79, msgSent=81, KBytesSent=142, KBytesReceived=156}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=999, doneAt=1081, sigs=615, msgReceived=145, msgSent=62, KBytesSent=234, KBytesReceived=271}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=1499, doneAt=1098, sigs=769, msgReceived=103, msgSent=80, KBytesSent=548, KBytesReceived=666}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=1999, doneAt=1198, sigs=1383, msgReceived=302, msgSent=169, KBytesSent=1103, KBytesReceived=1101}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=2499, doneAt=1510, sigs=1277, msgReceived=212, msgSent=341, KBytesSent=1050, KBytesReceived=959}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=2999, doneAt=1365, sigs=1771, msgReceived=168, msgSent=106, KBytesSent=1237, KBytesReceived=1373}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=3499, doneAt=1368, sigs=1830, msgReceived=576, msgSent=377, KBytesSent=2616, KBytesReceived=2773}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=3999, doneAt=1604, sigs=2091, msgReceived=271, msgSent=107, KBytesSent=2065, KBytesReceived=2280}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=4499, doneAt=1511, sigs=2325, msgReceived=219, msgSent=147, KBytesSent=1780, KBytesReceived=1901}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=4999, doneAt=1556, sigs=2634, msgReceived=369, msgSent=544, KBytesSent=3264, KBytesReceived=3299}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=5499, doneAt=1793, sigs=2800, msgReceived=438, msgSent=142, KBytesSent=3161, KBytesReceived=3586}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=5999, doneAt=1595, sigs=3185, msgReceived=477, msgSent=152, KBytesSent=3268, KBytesReceived=3528}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=6499, doneAt=1687, sigs=3720, msgReceived=487, msgSent=950, KBytesSent=5743, KBytesReceived=4585}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=6999, doneAt=1822, sigs=3584, msgReceived=340, msgSent=143, KBytesSent=4080, KBytesReceived=4523}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=7499, doneAt=1964, sigs=3940, msgReceived=383, msgSent=239, KBytesSent=3079, KBytesReceived=3666}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=7999, doneAt=1849, sigs=4114, msgReceived=580, msgSent=146, KBytesSent=4430, KBytesReceived=5538}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=8499, doneAt=1876, sigs=4330, msgReceived=326, msgSent=231, KBytesSent=5245, KBytesReceived=5684}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=8999, doneAt=2031, sigs=4632, msgReceived=211, msgSent=385, KBytesSent=4275, KBytesReceived=3902}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=9499, doneAt=2030, sigs=4794, msgReceived=557, msgSent=134, KBytesSent=4486, KBytesReceived=5569}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=9999, doneAt=2124, sigs=5252, msgReceived=444, msgSent=261, KBytesSent=6574, KBytesReceived=6692}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=10499, doneAt=1932, sigs=5648, msgReceived=357, msgSent=668, KBytesSent=4492, KBytesReceived=3779}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=10999, doneAt=2159, sigs=5922, msgReceived=350, msgSent=223, KBytesSent=7930, KBytesReceived=8126}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=11499, doneAt=2057, sigs=5780, msgReceived=339, msgSent=161, KBytesSent=4462, KBytesReceived=4886}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=11999, doneAt=2232, sigs=6211, msgReceived=310, msgSent=79, KBytesSent=4586, KBytesReceived=5846}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=12499, doneAt=1663, sigs=6790, msgReceived=973, msgSent=261, KBytesSent=9503, KBytesReceived=10698}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=12999, doneAt=2323, sigs=6893, msgReceived=367, msgSent=407, KBytesSent=7262, KBytesReceived=6891}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=13499, doneAt=2185, sigs=6766, msgReceived=523, msgSent=89, KBytesSent=6349, KBytesReceived=7847}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=13999, doneAt=2333, sigs=7206, msgReceived=361, msgSent=219, KBytesSent=6637, KBytesReceived=7124}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=14499, doneAt=2184, sigs=7832, msgReceived=348, msgSent=972, KBytesSent=9318, KBytesReceived=7321}
cnt=10, sendPeriod=80 P2PSigNode{nodeId=14999, doneAt=2725, sigs=7603, msgReceived=488, msgSent=310, KBytesSent=8461, KBytesReceived=8957}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=499, doneAt=484, sigs=300, msgReceived=245, msgSent=213, KBytesSent=272, KBytesReceived=266}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=999, doneAt=529, sigs=507, msgReceived=424, msgSent=142, KBytesSent=508, KBytesReceived=661}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=1499, doneAt=614, sigs=1139, msgReceived=245, msgSent=131, KBytesSent=767, KBytesReceived=1029}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=1999, doneAt=599, sigs=1053, msgReceived=597, msgSent=260, KBytesSent=1595, KBytesReceived=1716}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=2499, doneAt=732, sigs=1997, msgReceived=407, msgSent=1083, KBytesSent=2194, KBytesReceived=1555}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=2999, doneAt=682, sigs=1537, msgReceived=363, msgSent=201, KBytesSent=1592, KBytesReceived=2395}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=3499, doneAt=631, sigs=1859, msgReceived=863, msgSent=461, KBytesSent=3787, KBytesReceived=4190}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=3999, doneAt=682, sigs=2010, msgReceived=482, msgSent=153, KBytesSent=2380, KBytesReceived=3206}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=4499, doneAt=785, sigs=2416, msgReceived=478, msgSent=480, KBytesSent=3034, KBytesReceived=3120}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=4999, doneAt=702, sigs=2676, msgReceived=795, msgSent=1249, KBytesSent=5288, KBytesReceived=4295}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=5499, doneAt=800, sigs=2799, msgReceived=641, msgSent=146, KBytesSent=4453, KBytesReceived=5519}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=5999, doneAt=875, sigs=3030, msgReceived=658, msgSent=238, KBytesSent=4257, KBytesReceived=5461}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=6499, doneAt=853, sigs=3363, msgReceived=812, msgSent=1230, KBytesSent=7164, KBytesReceived=6664}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=6999, doneAt=890, sigs=3543, msgReceived=513, msgSent=198, KBytesSent=5198, KBytesReceived=5603}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=7499, doneAt=984, sigs=6562, msgReceived=701, msgSent=489, KBytesSent=4808, KBytesReceived=5539}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=7999, doneAt=969, sigs=4083, msgReceived=810, msgSent=220, KBytesSent=6040, KBytesReceived=7989}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=8499, doneAt=847, sigs=4677, msgReceived=575, msgSent=574, KBytesSent=8745, KBytesReceived=8220}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=8999, doneAt=962, sigs=4527, msgReceived=408, msgSent=306, KBytesSent=5497, KBytesReceived=5501}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=9499, doneAt=953, sigs=5203, msgReceived=913, msgSent=291, KBytesSent=8401, KBytesReceived=9102}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=9999, doneAt=1015, sigs=7549, msgReceived=677, msgSent=697, KBytesSent=9222, KBytesReceived=9236}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=10499, doneAt=1016, sigs=6978, msgReceived=1046, msgSent=3020, KBytesSent=12719, KBytesReceived=7026}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=10999, doneAt=1022, sigs=5713, msgReceived=596, msgSent=232, KBytesSent=8949, KBytesReceived=10725}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=11499, doneAt=1133, sigs=8590, msgReceived=556, msgSent=312, KBytesSent=6666, KBytesReceived=7826}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=11999, doneAt=1135, sigs=6417, msgReceived=481, msgSent=195, KBytesSent=8432, KBytesReceived=8750}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=12499, doneAt=975, sigs=6332, msgReceived=823, msgSent=492, KBytesSent=11233, KBytesReceived=12293}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=12999, doneAt=1130, sigs=7424, msgReceived=761, msgSent=1047, KBytesSent=12153, KBytesReceived=10321}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=13499, doneAt=963, sigs=6812, msgReceived=716, msgSent=202, KBytesSent=9906, KBytesReceived=11816}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=13999, doneAt=1168, sigs=7343, msgReceived=522, msgSent=227, KBytesSent=8453, KBytesReceived=10951}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=14499, doneAt=1121, sigs=7986, msgReceived=646, msgSent=1140, KBytesSent=13337, KBytesReceived=11511}
cnt=15, sendPeriod=20 P2PSigNode{nodeId=14999, doneAt=1085, sigs=7714, msgReceived=670, msgSent=413, KBytesSent=10978, KBytesReceived=11392}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=499, doneAt=585, sigs=261, msgReceived=179, msgSent=147, KBytesSent=234, KBytesReceived=259}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=999, doneAt=726, sigs=513, msgReceived=323, msgSent=88, KBytesSent=537, KBytesReceived=659}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=1499, doneAt=736, sigs=764, msgReceived=198, msgSent=123, KBytesSent=871, KBytesReceived=1050}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=1999, doneAt=808, sigs=1028, msgReceived=500, msgSent=218, KBytesSent=1572, KBytesReceived=1660}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=2499, doneAt=859, sigs=1347, msgReceived=303, msgSent=861, KBytesSent=2169, KBytesReceived=1738}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=2999, doneAt=853, sigs=1567, msgReceived=287, msgSent=194, KBytesSent=2079, KBytesReceived=2304}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=3499, doneAt=790, sigs=1769, msgReceived=710, msgSent=278, KBytesSent=3578, KBytesReceived=4029}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=3999, doneAt=830, sigs=2006, msgReceived=405, msgSent=144, KBytesSent=2769, KBytesReceived=3036}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=4499, doneAt=1064, sigs=2335, msgReceived=414, msgSent=416, KBytesSent=2887, KBytesReceived=3057}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=4999, doneAt=996, sigs=2650, msgReceived=682, msgSent=1126, KBytesSent=5014, KBytesReceived=4384}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=5499, doneAt=1111, sigs=3052, msgReceived=521, msgSent=169, KBytesSent=4245, KBytesReceived=5186}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=5999, doneAt=1075, sigs=3172, msgReceived=584, msgSent=189, KBytesSent=4484, KBytesReceived=5441}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=6499, doneAt=1200, sigs=3333, msgReceived=663, msgSent=891, KBytesSent=6996, KBytesReceived=6677}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=6999, doneAt=1157, sigs=3577, msgReceived=416, msgSent=244, KBytesSent=5167, KBytesReceived=5465}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=7499, doneAt=1308, sigs=3826, msgReceived=603, msgSent=422, KBytesSent=4882, KBytesReceived=5152}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=7999, doneAt=1328, sigs=4307, msgReceived=712, msgSent=206, KBytesSent=6669, KBytesReceived=7617}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=8499, doneAt=1165, sigs=4496, msgReceived=483, msgSent=294, KBytesSent=7982, KBytesReceived=8202}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=8999, doneAt=1251, sigs=4578, msgReceived=357, msgSent=158, KBytesSent=5001, KBytesReceived=5719}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=9499, doneAt=1411, sigs=4939, msgReceived=784, msgSent=278, KBytesSent=7731, KBytesReceived=9130}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=9999, doneAt=1296, sigs=5111, msgReceived=593, msgSent=572, KBytesSent=9177, KBytesReceived=9436}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=10499, doneAt=1249, sigs=5840, msgReceived=795, msgSent=2407, KBytesSent=10360, KBytesReceived=7055}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=10999, doneAt=1300, sigs=5886, msgReceived=489, msgSent=216, KBytesSent=10083, KBytesReceived=10676}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=11499, doneAt=1502, sigs=5899, msgReceived=495, msgSent=191, KBytesSent=6692, KBytesReceived=7946}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=11999, doneAt=1535, sigs=6750, msgReceived=432, msgSent=116, KBytesSent=6897, KBytesReceived=8847}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=12499, doneAt=1409, sigs=6489, msgReceived=743, msgSent=331, KBytesSent=11444, KBytesReceived=11800}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=12999, doneAt=1439, sigs=7370, msgReceived=614, msgSent=904, KBytesSent=11876, KBytesReceived=9227}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=13499, doneAt=1359, sigs=7215, msgReceived=602, msgSent=188, KBytesSent=10277, KBytesReceived=11566}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=13999, doneAt=1464, sigs=7154, msgReceived=405, msgSent=185, KBytesSent=9898, KBytesReceived=10928}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=14499, doneAt=1576, sigs=7580, msgReceived=538, msgSent=596, KBytesSent=11323, KBytesReceived=10760}
cnt=15, sendPeriod=40 P2PSigNode{nodeId=14999, doneAt=1525, sigs=7575, msgReceived=614, msgSent=427, KBytesSent=10910, KBytesReceived=11207}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=499, doneAt=681, sigs=275, msgReceived=170, msgSent=144, KBytesSent=249, KBytesReceived=247}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=999, doneAt=799, sigs=531, msgReceived=276, msgSent=83, KBytesSent=537, KBytesReceived=597}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=1499, doneAt=918, sigs=847, msgReceived=179, msgSent=93, KBytesSent=959, KBytesReceived=1083}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=1999, doneAt=950, sigs=1060, msgReceived=445, msgSent=214, KBytesSent=1627, KBytesReceived=1718}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=2499, doneAt=1086, sigs=1459, msgReceived=287, msgSent=1037, KBytesSent=2262, KBytesReceived=1969}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=2999, doneAt=1023, sigs=1562, msgReceived=270, msgSent=131, KBytesSent=1976, KBytesReceived=2260}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=3499, doneAt=933, sigs=1984, msgReceived=647, msgSent=273, KBytesSent=3922, KBytesReceived=4431}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=3999, doneAt=1079, sigs=2026, msgReceived=388, msgSent=140, KBytesSent=2696, KBytesReceived=3110}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=4499, doneAt=1175, sigs=2288, msgReceived=383, msgSent=306, KBytesSent=2879, KBytesReceived=3003}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=4999, doneAt=1277, sigs=2598, msgReceived=633, msgSent=1198, KBytesSent=5124, KBytesReceived=4569}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=5499, doneAt=1273, sigs=2811, msgReceived=502, msgSent=129, KBytesSent=4650, KBytesReceived=5224}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=5999, doneAt=1248, sigs=3124, msgReceived=538, msgSent=185, KBytesSent=4724, KBytesReceived=5281}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=6499, doneAt=1504, sigs=3325, msgReceived=610, msgSent=599, KBytesSent=6753, KBytesReceived=7017}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=6999, doneAt=1398, sigs=3526, msgReceived=378, msgSent=177, KBytesSent=5052, KBytesReceived=5553}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=7499, doneAt=1700, sigs=3977, msgReceived=603, msgSent=444, KBytesSent=4936, KBytesReceived=5195}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=7999, doneAt=1472, sigs=4055, msgReceived=625, msgSent=199, KBytesSent=6489, KBytesReceived=7345}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=8499, doneAt=1595, sigs=4502, msgReceived=476, msgSent=291, KBytesSent=7791, KBytesReceived=8092}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=8999, doneAt=1512, sigs=4664, msgReceived=332, msgSent=155, KBytesSent=5751, KBytesReceived=6180}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=9499, doneAt=1615, sigs=5029, msgReceived=745, msgSent=237, KBytesSent=8306, KBytesReceived=8534}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=9999, doneAt=1752, sigs=5057, msgReceived=619, msgSent=641, KBytesSent=9178, KBytesReceived=9427}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=10499, doneAt=1494, sigs=6859, msgReceived=700, msgSent=2239, KBytesSent=11421, KBytesReceived=7103}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=10999, doneAt=1564, sigs=5553, msgReceived=446, msgSent=173, KBytesSent=9484, KBytesReceived=10406}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=11499, doneAt=1805, sigs=5832, msgReceived=467, msgSent=186, KBytesSent=7193, KBytesReceived=7803}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=11999, doneAt=1832, sigs=6578, msgReceived=435, msgSent=111, KBytesSent=7696, KBytesReceived=8779}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=12499, doneAt=1824, sigs=6715, msgReceived=754, msgSent=326, KBytesSent=11674, KBytesReceived=11356}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=12999, doneAt=1836, sigs=6748, msgReceived=611, msgSent=898, KBytesSent=10819, KBytesReceived=9269}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=13499, doneAt=1698, sigs=6789, msgReceived=575, msgSent=183, KBytesSent=9991, KBytesReceived=11229}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=13999, doneAt=1738, sigs=7311, msgReceived=390, msgSent=178, KBytesSent=10119, KBytesReceived=10940}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=14499, doneAt=1862, sigs=7753, msgReceived=507, msgSent=589, KBytesSent=12175, KBytesReceived=11150}
cnt=15, sendPeriod=60 P2PSigNode{nodeId=14999, doneAt=1852, sigs=7656, msgReceived=566, msgSent=331, KBytesSent=10890, KBytesReceived=11443}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=499, doneAt=711, sigs=256, msgReceived=136, msgSent=85, KBytesSent=229, KBytesReceived=248}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=999, doneAt=912, sigs=812, msgReceived=278, msgSent=106, KBytesSent=545, KBytesReceived=608}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=1499, doneAt=1099, sigs=754, msgReceived=170, msgSent=93, KBytesSent=890, KBytesReceived=1023}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=1999, doneAt=1109, sigs=1360, msgReceived=421, msgSent=212, KBytesSent=2072, KBytesReceived=1936}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=2499, doneAt=981, sigs=1313, msgReceived=254, msgSent=852, KBytesSent=2101, KBytesReceived=1628}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=2999, doneAt=1193, sigs=1650, msgReceived=252, msgSent=131, KBytesSent=2252, KBytesReceived=2289}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=3499, doneAt=1133, sigs=1992, msgReceived=645, msgSent=315, KBytesSent=3706, KBytesReceived=4106}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=3999, doneAt=1252, sigs=2031, msgReceived=393, msgSent=138, KBytesSent=2752, KBytesReceived=3101}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=4499, doneAt=1431, sigs=2327, msgReceived=384, msgSent=330, KBytesSent=2947, KBytesReceived=3052}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=4999, doneAt=1440, sigs=2586, msgReceived=608, msgSent=1195, KBytesSent=5091, KBytesReceived=4589}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=5499, doneAt=1449, sigs=2906, msgReceived=489, msgSent=126, KBytesSent=4834, KBytesReceived=5286}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=5999, doneAt=1428, sigs=3607, msgReceived=523, msgSent=182, KBytesSent=5431, KBytesReceived=5373}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=6499, doneAt=1706, sigs=3390, msgReceived=563, msgSent=513, KBytesSent=6673, KBytesReceived=6689}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=6999, doneAt=1614, sigs=3848, msgReceived=360, msgSent=174, KBytesSent=5502, KBytesReceived=5571}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=7499, doneAt=2034, sigs=3864, msgReceived=635, msgSent=493, KBytesSent=4843, KBytesReceived=5015}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=7999, doneAt=1801, sigs=4005, msgReceived=640, msgSent=162, KBytesSent=6410, KBytesReceived=7406}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=8499, doneAt=1822, sigs=4283, msgReceived=440, msgSent=326, KBytesSent=7673, KBytesReceived=8161}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=8999, doneAt=1681, sigs=4713, msgReceived=327, msgSent=151, KBytesSent=5833, KBytesReceived=6035}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=9499, doneAt=1942, sigs=4802, msgReceived=762, msgSent=269, KBytesSent=8019, KBytesReceived=8730}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=9999, doneAt=1966, sigs=5179, msgReceived=597, msgSent=601, KBytesSent=9338, KBytesReceived=9519}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=10499, doneAt=1685, sigs=5850, msgReceived=650, msgSent=2235, KBytesSent=10158, KBytesReceived=6917}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=10999, doneAt=1860, sigs=5666, msgReceived=436, msgSent=170, KBytesSent=9682, KBytesReceived=10440}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=11499, doneAt=2099, sigs=5965, msgReceived=459, msgSent=181, KBytesSent=7074, KBytesReceived=7987}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=11999, doneAt=2148, sigs=6711, msgReceived=418, msgSent=138, KBytesSent=8525, KBytesReceived=8853}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=12499, doneAt=2145, sigs=6274, msgReceived=731, msgSent=322, KBytesSent=10942, KBytesReceived=12029}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=12999, doneAt=2131, sigs=7168, msgReceived=567, msgSent=771, KBytesSent=11478, KBytesReceived=9620}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=13499, doneAt=2096, sigs=7273, msgReceived=566, msgSent=181, KBytesSent=10697, KBytesReceived=11081}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=13999, doneAt=2106, sigs=7272, msgReceived=359, msgSent=175, KBytesSent=10069, KBytesReceived=10946}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=14499, doneAt=2202, sigs=11019, msgReceived=476, msgSent=524, KBytesSent=12095, KBytesReceived=11385}
cnt=15, sendPeriod=80 P2PSigNode{nodeId=14999, doneAt=2262, sigs=8346, msgReceived=600, msgSent=358, KBytesSent=11644, KBytesReceived=11816}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=499, doneAt=372, sigs=252, msgReceived=319, msgSent=206, KBytesSent=301, KBytesReceived=346}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=999, doneAt=536, sigs=510, msgReceived=455, msgSent=184, KBytesSent=731, KBytesReceived=864}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=1499, doneAt=548, sigs=796, msgReceived=326, msgSent=250, KBytesSent=1257, KBytesReceived=1442}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=1999, doneAt=573, sigs=1022, msgReceived=763, msgSent=376, KBytesSent=2290, KBytesReceived=2556}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=2499, doneAt=629, sigs=1327, msgReceived=466, msgSent=1475, KBytesSent=2815, KBytesReceived=2133}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=2999, doneAt=597, sigs=1637, msgReceived=543, msgSent=356, KBytesSent=3120, KBytesReceived=3340}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=3499, doneAt=671, sigs=1810, msgReceived=907, msgSent=274, KBytesSent=3757, KBytesReceived=4820}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=3999, doneAt=606, sigs=2031, msgReceived=675, msgSent=429, KBytesSent=4723, KBytesReceived=5297}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=4499, doneAt=744, sigs=2265, msgReceived=497, msgSent=451, KBytesSent=3378, KBytesReceived=3558}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=4999, doneAt=719, sigs=3217, msgReceived=881, msgSent=1433, KBytesSent=7773, KBytesReceived=5841}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=5499, doneAt=739, sigs=2798, msgReceived=600, msgSent=165, KBytesSent=5610, KBytesReceived=6219}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=5999, doneAt=705, sigs=3038, msgReceived=762, msgSent=206, KBytesSent=5947, KBytesReceived=6899}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=6499, doneAt=750, sigs=3471, msgReceived=781, msgSent=948, KBytesSent=8308, KBytesReceived=7953}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=6999, doneAt=836, sigs=3539, msgReceived=548, msgSent=366, KBytesSent=6696, KBytesReceived=7333}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=7499, doneAt=912, sigs=4011, msgReceived=787, msgSent=651, KBytesSent=7328, KBytesReceived=7062}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=7999, doneAt=872, sigs=4009, msgReceived=884, msgSent=266, KBytesSent=8071, KBytesReceived=9813}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=8499, doneAt=895, sigs=4338, msgReceived=845, msgSent=479, KBytesSent=8792, KBytesReceived=11011}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=8999, doneAt=920, sigs=4612, msgReceived=562, msgSent=351, KBytesSent=7498, KBytesReceived=8187}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=9499, doneAt=947, sigs=4792, msgReceived=1029, msgSent=315, KBytesSent=9744, KBytesReceived=11851}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=9999, doneAt=993, sigs=7775, msgReceived=772, msgSent=923, KBytesSent=13064, KBytesReceived=11896}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=10499, doneAt=898, sigs=7779, msgReceived=1115, msgSent=3578, KBytesSent=18159, KBytesReceived=8294}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=10999, doneAt=1006, sigs=5974, msgReceived=752, msgSent=287, KBytesSent=12703, KBytesReceived=13981}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=11499, doneAt=1036, sigs=5974, msgReceived=853, msgSent=318, KBytesSent=11783, KBytesReceived=14059}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=11999, doneAt=916, sigs=6062, msgReceived=825, msgSent=229, KBytesSent=10515, KBytesReceived=12054}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=12499, doneAt=1063, sigs=9451, msgReceived=1129, msgSent=456, KBytesSent=13508, KBytesReceived=15647}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=12999, doneAt=1092, sigs=8125, msgReceived=832, msgSent=1452, KBytesSent=15364, KBytesReceived=12720}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=13499, doneAt=1039, sigs=7137, msgReceived=935, msgSent=254, KBytesSent=13030, KBytesReceived=15489}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=13999, doneAt=911, sigs=7690, msgReceived=812, msgSent=188, KBytesSent=12709, KBytesReceived=14107}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=14499, doneAt=1141, sigs=7282, msgReceived=675, msgSent=585, KBytesSent=12386, KBytesReceived=14234}
cnt=20, sendPeriod=20 P2PSigNode{nodeId=14999, doneAt=1026, sigs=7582, msgReceived=939, msgSent=436, KBytesSent=15100, KBytesReceived=16169}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=499, doneAt=557, sigs=384, msgReceived=253, msgSent=175, KBytesSent=309, KBytesReceived=365}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=999, doneAt=678, sigs=537, msgReceived=367, msgSent=175, KBytesSent=760, KBytesReceived=828}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=1499, doneAt=718, sigs=772, msgReceived=269, msgSent=205, KBytesSent=1290, KBytesReceived=1441}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=1999, doneAt=735, sigs=1003, msgReceived=619, msgSent=216, KBytesSent=2235, KBytesReceived=2550}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=2499, doneAt=749, sigs=1296, msgReceived=387, msgSent=1235, KBytesSent=2666, KBytesReceived=2197}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=2999, doneAt=803, sigs=1518, msgReceived=440, msgSent=224, KBytesSent=2875, KBytesReceived=3266}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=3499, doneAt=781, sigs=1879, msgReceived=730, msgSent=263, KBytesSent=4227, KBytesReceived=4593}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=3999, doneAt=857, sigs=2016, msgReceived=592, msgSent=418, KBytesSent=4495, KBytesReceived=5317}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=4499, doneAt=962, sigs=2403, msgReceived=413, msgSent=248, KBytesSent=3669, KBytesReceived=3600}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=4999, doneAt=972, sigs=2560, msgReceived=773, msgSent=1273, KBytesSent=6524, KBytesReceived=5661}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=5499, doneAt=992, sigs=2816, msgReceived=466, msgSent=152, KBytesSent=5398, KBytesReceived=6118}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=5999, doneAt=936, sigs=3053, msgReceived=614, msgSent=194, KBytesSent=5890, KBytesReceived=6718}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=6499, doneAt=1019, sigs=3415, msgReceived=589, msgSent=793, KBytesSent=8222, KBytesReceived=7744}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=6999, doneAt=1072, sigs=5273, msgReceived=447, msgSent=313, KBytesSent=6941, KBytesReceived=7464}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=7499, doneAt=1138, sigs=3812, msgReceived=678, msgSent=712, KBytesSent=7265, KBytesReceived=7132}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=7999, doneAt=1179, sigs=4002, msgReceived=776, msgSent=253, KBytesSent=8336, KBytesReceived=9627}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=8499, doneAt=1238, sigs=4262, msgReceived=726, msgSent=471, KBytesSent=9922, KBytesReceived=10606}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=8999, doneAt=1237, sigs=4874, msgReceived=524, msgSent=234, KBytesSent=7629, KBytesReceived=8261}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=9499, doneAt=1329, sigs=4833, msgReceived=859, msgSent=303, KBytesSent=10350, KBytesReceived=11106}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=9999, doneAt=1275, sigs=5138, msgReceived=682, msgSent=675, KBytesSent=11521, KBytesReceived=11874}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=10499, doneAt=1127, sigs=6136, msgReceived=830, msgSent=2878, KBytesSent=14470, KBytesReceived=10074}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=10999, doneAt=1194, sigs=5560, msgReceived=606, msgSent=220, KBytesSent=12137, KBytesReceived=13435}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=11499, doneAt=1393, sigs=5761, msgReceived=750, msgSent=214, KBytesSent=11931, KBytesReceived=13369}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=11999, doneAt=1201, sigs=6185, msgReceived=741, msgSent=214, KBytesSent=10815, KBytesReceived=11832}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=12499, doneAt=1388, sigs=6416, msgReceived=987, msgSent=349, KBytesSent=13756, KBytesReceived=15306}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=12999, doneAt=1325, sigs=6890, msgReceived=698, msgSent=953, KBytesSent=14298, KBytesReceived=13113}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=13499, doneAt=1347, sigs=7498, msgReceived=804, msgSent=156, KBytesSent=14036, KBytesReceived=15270}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=13999, doneAt=1163, sigs=7556, msgReceived=660, msgSent=173, KBytesSent=12566, KBytesReceived=13802}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=14499, doneAt=1481, sigs=7304, msgReceived=597, msgSent=379, KBytesSent=13500, KBytesReceived=14020}
cnt=20, sendPeriod=40 P2PSigNode{nodeId=14999, doneAt=1415, sigs=7528, msgReceived=898, msgSent=594, KBytesSent=15883, KBytesReceived=16920}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=499, doneAt=563, sigs=251, msgReceived=223, msgSent=116, KBytesSent=298, KBytesReceived=346}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=999, doneAt=741, sigs=538, msgReceived=327, msgSent=139, KBytesSent=783, KBytesReceived=822}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=1499, doneAt=835, sigs=754, msgReceived=256, msgSent=163, KBytesSent=1254, KBytesReceived=1444}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=1999, doneAt=825, sigs=1045, msgReceived=552, msgSent=264, KBytesSent=2457, KBytesReceived=2549}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=2499, doneAt=917, sigs=1322, msgReceived=364, msgSent=1307, KBytesSent=2728, KBytesReceived=2190}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=2999, doneAt=994, sigs=1538, msgReceived=390, msgSent=221, KBytesSent=2919, KBytesReceived=3238}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=3499, doneAt=936, sigs=1764, msgReceived=690, msgSent=261, KBytesSent=4057, KBytesReceived=4563}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=3999, doneAt=1045, sigs=2023, msgReceived=565, msgSent=465, KBytesSent=4582, KBytesReceived=5140}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=4499, doneAt=1182, sigs=2401, msgReceived=408, msgSent=276, KBytesSent=3694, KBytesReceived=3758}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=4999, doneAt=1129, sigs=2569, msgReceived=716, msgSent=1368, KBytesSent=6602, KBytesReceived=5646}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=5499, doneAt=1140, sigs=2882, msgReceived=453, msgSent=148, KBytesSent=5804, KBytesReceived=6245}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=5999, doneAt=1109, sigs=3151, msgReceived=614, msgSent=190, KBytesSent=6233, KBytesReceived=6890}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=6499, doneAt=1293, sigs=3335, msgReceived=545, msgSent=597, KBytesSent=7747, KBytesReceived=7790}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=6999, doneAt=1254, sigs=3677, msgReceived=397, msgSent=265, KBytesSent=6884, KBytesReceived=7269}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=7499, doneAt=1460, sigs=4046, msgReceived=660, msgSent=632, KBytesSent=7595, KBytesReceived=7250}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=7999, doneAt=1429, sigs=6184, msgReceived=753, msgSent=294, KBytesSent=9046, KBytesReceived=9609}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=8499, doneAt=1552, sigs=5934, msgReceived=694, msgSent=319, KBytesSent=9977, KBytesReceived=11006}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=8999, doneAt=1454, sigs=6769, msgReceived=512, msgSent=262, KBytesSent=7532, KBytesReceived=8028}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=9499, doneAt=1570, sigs=4875, msgReceived=835, msgSent=296, KBytesSent=10446, KBytesReceived=11103}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=9999, doneAt=1496, sigs=5063, msgReceived=650, msgSent=715, KBytesSent=11652, KBytesReceived=11715}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=10499, doneAt=1474, sigs=5692, msgReceived=802, msgSent=2988, KBytesSent=13667, KBytesReceived=9790}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=10999, doneAt=1354, sigs=5562, msgReceived=530, msgSent=214, KBytesSent=12419, KBytesReceived=13463}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=11499, doneAt=1731, sigs=5758, msgReceived=692, msgSent=207, KBytesSent=11681, KBytesReceived=13569}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=11999, doneAt=1547, sigs=6035, msgReceived=716, msgSent=211, KBytesSent=10554, KBytesReceived=11416}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=12499, doneAt=1590, sigs=6622, msgReceived=956, msgSent=251, KBytesSent=14108, KBytesReceived=14980}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=12999, doneAt=1533, sigs=6935, msgReceived=626, msgSent=945, KBytesSent=14058, KBytesReceived=13221}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=13499, doneAt=1699, sigs=7013, msgReceived=777, msgSent=149, KBytesSent=12793, KBytesReceived=15069}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=13999, doneAt=1478, sigs=7653, msgReceived=653, msgSent=132, KBytesSent=12318, KBytesReceived=13281}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=14499, doneAt=1804, sigs=8082, msgReceived=525, msgSent=333, KBytesSent=14456, KBytesReceived=14644}
cnt=20, sendPeriod=60 P2PSigNode{nodeId=14999, doneAt=1717, sigs=7507, msgReceived=823, msgSent=459, KBytesSent=15662, KBytesReceived=16804}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=499, doneAt=669, sigs=266, msgReceived=211, msgSent=114, KBytesSent=297, KBytesReceived=351}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=999, doneAt=805, sigs=541, msgReceived=306, msgSent=137, KBytesSent=790, KBytesReceived=814}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=1499, doneAt=1043, sigs=755, msgReceived=250, msgSent=164, KBytesSent=1325, KBytesReceived=1419}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=1999, doneAt=997, sigs=1085, msgReceived=532, msgSent=262, KBytesSent=2493, KBytesReceived=2838}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=2499, doneAt=995, sigs=1315, msgReceived=338, msgSent=1305, KBytesSent=2719, KBytesReceived=2207}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=2999, doneAt=1073, sigs=1557, msgReceived=408, msgSent=218, KBytesSent=2957, KBytesReceived=3169}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=3499, doneAt=1034, sigs=1759, msgReceived=658, msgSent=257, KBytesSent=3980, KBytesReceived=4437}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=3999, doneAt=1167, sigs=2014, msgReceived=512, msgSent=413, KBytesSent=4649, KBytesReceived=5058}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=4499, doneAt=1328, sigs=2448, msgReceived=386, msgSent=272, KBytesSent=3661, KBytesReceived=3753}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=4999, doneAt=1295, sigs=2552, msgReceived=701, msgSent=1565, KBytesSent=6599, KBytesReceived=6097}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=5499, doneAt=1361, sigs=2812, msgReceived=427, msgSent=146, KBytesSent=5657, KBytesReceived=6100}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=5999, doneAt=1357, sigs=3137, msgReceived=615, msgSent=189, KBytesSent=6354, KBytesReceived=6953}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=6499, doneAt=1587, sigs=3359, msgReceived=558, msgSent=499, KBytesSent=7722, KBytesReceived=7737}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=6999, doneAt=1272, sigs=3605, msgReceived=368, msgSent=220, KBytesSent=6888, KBytesReceived=7230}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=7499, doneAt=1735, sigs=4405, msgReceived=644, msgSent=668, KBytesSent=7924, KBytesReceived=7373}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=7999, doneAt=1667, sigs=4088, msgReceived=732, msgSent=244, KBytesSent=8330, KBytesReceived=9383}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=8499, doneAt=1771, sigs=4519, msgReceived=668, msgSent=216, KBytesSent=10052, KBytesReceived=11104}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=8999, doneAt=1598, sigs=4625, msgReceived=501, msgSent=190, KBytesSent=7466, KBytesReceived=7920}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=9499, doneAt=1879, sigs=7094, msgReceived=804, msgSent=338, KBytesSent=14847, KBytesReceived=11049}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=9999, doneAt=1808, sigs=5298, msgReceived=673, msgSent=897, KBytesSent=12390, KBytesReceived=11290}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=10499, doneAt=1604, sigs=5685, msgReceived=714, msgSent=2604, KBytesSent=13327, KBytesReceived=9816}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=10999, doneAt=1673, sigs=5851, msgReceived=551, msgSent=261, KBytesSent=12873, KBytesReceived=13599}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=11499, doneAt=1877, sigs=5820, msgReceived=650, msgSent=203, KBytesSent=12101, KBytesReceived=13238}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=11999, doneAt=1751, sigs=6134, msgReceived=674, msgSent=168, KBytesSent=10094, KBytesReceived=11844}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=12499, doneAt=1905, sigs=7205, msgReceived=937, msgSent=294, KBytesSent=15389, KBytesReceived=16127}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=12999, doneAt=1836, sigs=6602, msgReceived=621, msgSent=943, KBytesSent=13735, KBytesReceived=13251}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=13499, doneAt=1967, sigs=6825, msgReceived=740, msgSent=147, KBytesSent=12790, KBytesReceived=14919}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=13999, doneAt=1568, sigs=7005, msgReceived=585, msgSent=127, KBytesSent=11307, KBytesReceived=12855}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=14499, doneAt=2030, sigs=7534, msgReceived=519, msgSent=291, KBytesSent=13434, KBytesReceived=14146}
cnt=20, sendPeriod=80 P2PSigNode{nodeId=14999, doneAt=1949, sigs=7988, msgReceived=811, msgSent=498, KBytesSent=16696, KBytesReceived=16755}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=499, doneAt=376, sigs=252, msgReceived=401, msgSent=338, KBytesSent=473, KBytesReceived=532}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=999, doneAt=474, sigs=606, msgReceived=522, msgSent=192, KBytesSent=1120, KBytesReceived=1126}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=1499, doneAt=525, sigs=930, msgReceived=513, msgSent=318, KBytesSent=1778, KBytesReceived=1908}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=1999, doneAt=525, sigs=1032, msgReceived=834, msgSent=452, KBytesSent=2936, KBytesReceived=3169}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=2499, doneAt=616, sigs=2067, msgReceived=515, msgSent=1684, KBytesSent=4782, KBytesReceived=2782}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=2999, doneAt=639, sigs=1515, msgReceived=655, msgSent=375, KBytesSent=3504, KBytesReceived=3947}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=3499, doneAt=623, sigs=1815, msgReceived=841, msgSent=324, KBytesSent=4849, KBytesReceived=5470}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=3999, doneAt=650, sigs=2041, msgReceived=826, msgSent=631, KBytesSent=5825, KBytesReceived=6064}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=4499, doneAt=689, sigs=2534, msgReceived=705, msgSent=421, KBytesSent=5215, KBytesReceived=5032}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=4999, doneAt=717, sigs=3939, msgReceived=957, msgSent=2657, KBytesSent=10221, KBytesReceived=6503}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=5499, doneAt=727, sigs=2811, msgReceived=673, msgSent=246, KBytesSent=6895, KBytesReceived=7689}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=5999, doneAt=738, sigs=3028, msgReceived=737, msgSent=294, KBytesSent=7158, KBytesReceived=8154}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=6499, doneAt=706, sigs=3355, msgReceived=760, msgSent=805, KBytesSent=9185, KBytesReceived=9256}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=6999, doneAt=751, sigs=3516, msgReceived=810, msgSent=186, KBytesSent=7593, KBytesReceived=9522}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=7499, doneAt=838, sigs=4088, msgReceived=788, msgSent=568, KBytesSent=9506, KBytesReceived=9147}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=7999, doneAt=831, sigs=4077, msgReceived=1001, msgSent=262, KBytesSent=10138, KBytesReceived=11667}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=8499, doneAt=804, sigs=4271, msgReceived=915, msgSent=443, KBytesSent=11395, KBytesReceived=13070}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=8999, doneAt=882, sigs=7045, msgReceived=741, msgSent=294, KBytesSent=10005, KBytesReceived=10250}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=9499, doneAt=808, sigs=5066, msgReceived=1034, msgSent=368, KBytesSent=12714, KBytesReceived=13106}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=9999, doneAt=831, sigs=5127, msgReceived=1083, msgSent=911, KBytesSent=14808, KBytesReceived=15093}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=10499, doneAt=887, sigs=6344, msgReceived=1285, msgSent=4507, KBytesSent=19785, KBytesReceived=10608}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=10999, doneAt=832, sigs=5768, msgReceived=845, msgSent=278, KBytesSent=15584, KBytesReceived=17501}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=11499, doneAt=931, sigs=5945, msgReceived=967, msgSent=272, KBytesSent=15341, KBytesReceived=17428}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=11999, doneAt=880, sigs=6708, msgReceived=788, msgSent=180, KBytesSent=14118, KBytesReceived=14921}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=12499, doneAt=872, sigs=6935, msgReceived=1150, msgSent=564, KBytesSent=19010, KBytesReceived=19364}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=12999, doneAt=903, sigs=6533, msgReceived=795, msgSent=582, KBytesSent=15390, KBytesReceived=15930}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=13499, doneAt=982, sigs=7033, msgReceived=1073, msgSent=259, KBytesSent=17094, KBytesReceived=19494}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=13999, doneAt=822, sigs=7169, msgReceived=881, msgSent=217, KBytesSent=13927, KBytesReceived=16333}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=14499, doneAt=993, sigs=7523, msgReceived=729, msgSent=816, KBytesSent=17433, KBytesReceived=16445}
cnt=25, sendPeriod=20 P2PSigNode{nodeId=14999, doneAt=1059, sigs=7519, msgReceived=933, msgSent=889, KBytesSent=19665, KBytesReceived=20653}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=499, doneAt=446, sigs=264, msgReceived=310, msgSent=211, KBytesSent=490, KBytesReceived=518}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=999, doneAt=582, sigs=510, msgReceived=381, msgSent=184, KBytesSent=978, KBytesReceived=1092}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=1499, doneAt=684, sigs=1198, msgReceived=419, msgSent=212, KBytesSent=1863, KBytesReceived=1862}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=1999, doneAt=687, sigs=1006, msgReceived=658, msgSent=443, KBytesSent=2871, KBytesReceived=3060}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=2499, doneAt=756, sigs=1438, msgReceived=382, msgSent=1075, KBytesSent=3297, KBytesReceived=2620}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=2999, doneAt=795, sigs=1510, msgReceived=530, msgSent=265, KBytesSent=3487, KBytesReceived=3855}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=3499, doneAt=838, sigs=2764, msgReceived=716, msgSent=374, KBytesSent=4897, KBytesReceived=5408}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=3999, doneAt=885, sigs=3050, msgReceived=707, msgSent=501, KBytesSent=5964, KBytesReceived=6370}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=4499, doneAt=892, sigs=2311, msgReceived=585, msgSent=322, KBytesSent=4642, KBytesReceived=4974}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=4999, doneAt=932, sigs=2884, msgReceived=830, msgSent=1974, KBytesSent=9168, KBytesReceived=7009}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=5499, doneAt=925, sigs=4132, msgReceived=556, msgSent=340, KBytesSent=7025, KBytesReceived=7421}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=5999, doneAt=953, sigs=3045, msgReceived=597, msgSent=334, KBytesSent=7257, KBytesReceived=7987}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=6499, doneAt=964, sigs=3507, msgReceived=617, msgSent=409, KBytesSent=9268, KBytesReceived=9262}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=6999, doneAt=975, sigs=3607, msgReceived=676, msgSent=176, KBytesSent=8410, KBytesReceived=9179}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=7499, doneAt=1033, sigs=3940, msgReceived=666, msgSent=409, KBytesSent=9104, KBytesReceived=9364}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=7999, doneAt=1114, sigs=4021, msgReceived=874, msgSent=249, KBytesSent=10050, KBytesReceived=11325}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=8499, doneAt=1077, sigs=4369, msgReceived=753, msgSent=316, KBytesSent=11832, KBytesReceived=12538}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=8999, doneAt=1145, sigs=4755, msgReceived=653, msgSent=196, KBytesSent=9142, KBytesReceived=9880}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=9499, doneAt=1203, sigs=4798, msgReceived=936, msgSent=525, KBytesSent=12723, KBytesReceived=13371}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=9999, doneAt=1152, sigs=5134, msgReceived=911, msgSent=1015, KBytesSent=15006, KBytesReceived=14836}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=10499, doneAt=1252, sigs=6020, msgReceived=1114, msgSent=4255, KBytesSent=18795, KBytesReceived=11462}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=10999, doneAt=1141, sigs=5811, msgReceived=699, msgSent=267, KBytesSent=16263, KBytesReceived=17351}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=11499, doneAt=1280, sigs=6108, msgReceived=853, msgSent=260, KBytesSent=16407, KBytesReceived=17831}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=11999, doneAt=1255, sigs=6034, msgReceived=683, msgSent=167, KBytesSent=12451, KBytesReceived=14456}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=12499, doneAt=1196, sigs=6737, msgReceived=1025, msgSent=377, KBytesSent=18316, KBytesReceived=18829}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=12999, doneAt=1300, sigs=6763, msgReceived=679, msgSent=571, KBytesSent=16227, KBytesReceived=16379}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=13499, doneAt=1343, sigs=6879, msgReceived=879, msgSent=192, KBytesSent=17089, KBytesReceived=19103}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=13999, doneAt=1164, sigs=7643, msgReceived=777, msgSent=163, KBytesSent=15510, KBytesReceived=16547}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=14499, doneAt=1190, sigs=7435, msgReceived=589, msgSent=460, KBytesSent=16690, KBytesReceived=16807}
cnt=25, sendPeriod=40 P2PSigNode{nodeId=14999, doneAt=1329, sigs=7590, msgReceived=774, msgSent=816, KBytesSent=20829, KBytesReceived=21518}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=499, doneAt=525, sigs=305, msgReceived=284, msgSent=169, KBytesSent=564, KBytesReceived=556}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=999, doneAt=742, sigs=527, msgReceived=355, msgSent=182, KBytesSent=1016, KBytesReceived=1060}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=1499, doneAt=763, sigs=771, msgReceived=394, msgSent=159, KBytesSent=1740, KBytesReceived=1823}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=1999, doneAt=810, sigs=1013, msgReceived=625, msgSent=439, KBytesSent=2855, KBytesReceived=3116}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=2499, doneAt=916, sigs=1491, msgReceived=378, msgSent=1072, KBytesSent=3433, KBytesReceived=2885}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=2999, doneAt=963, sigs=2426, msgReceived=491, msgSent=309, KBytesSent=3902, KBytesReceived=3933}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=3499, doneAt=968, sigs=1757, msgReceived=673, msgSent=310, KBytesSent=4814, KBytesReceived=5273}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=3999, doneAt=944, sigs=2007, msgReceived=622, msgSent=435, KBytesSent=5681, KBytesReceived=6095}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=4499, doneAt=1046, sigs=2265, msgReceived=546, msgSent=275, KBytesSent=4552, KBytesReceived=4618}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=4999, doneAt=1183, sigs=2569, msgReceived=824, msgSent=2276, KBytesSent=8511, KBytesReceived=7620}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=5499, doneAt=1070, sigs=2771, msgReceived=500, msgSent=229, KBytesSent=6831, KBytesReceived=7271}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=5999, doneAt=1168, sigs=3219, msgReceived=568, msgSent=331, KBytesSent=7377, KBytesReceived=7902}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=6499, doneAt=1129, sigs=3591, msgReceived=562, msgSent=459, KBytesSent=9536, KBytesReceived=9355}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=6999, doneAt=1099, sigs=3647, msgReceived=654, msgSent=171, KBytesSent=8699, KBytesReceived=9335}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=7499, doneAt=1377, sigs=3821, msgReceived=684, msgSent=551, KBytesSent=8978, KBytesReceived=9653}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=7999, doneAt=1306, sigs=4059, msgReceived=772, msgSent=243, KBytesSent=10173, KBytesReceived=11695}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=8499, doneAt=1251, sigs=4578, msgReceived=716, msgSent=253, KBytesSent=12558, KBytesReceived=13078}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=8999, doneAt=1273, sigs=4526, msgReceived=605, msgSent=189, KBytesSent=8934, KBytesReceived=9814}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=9499, doneAt=1529, sigs=4834, msgReceived=927, msgSent=576, KBytesSent=12898, KBytesReceived=13474}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=9999, doneAt=1369, sigs=5113, msgReceived=903, msgSent=1183, KBytesSent=15134, KBytesReceived=14903}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=10499, doneAt=1481, sigs=5595, msgReceived=1001, msgSent=3913, KBytesSent=17450, KBytesReceived=12016}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=10999, doneAt=1350, sigs=5871, msgReceived=647, msgSent=261, KBytesSent=16167, KBytesReceived=17154}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=11499, doneAt=1394, sigs=5987, msgReceived=811, msgSent=251, KBytesSent=16099, KBytesReceived=17303}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=11999, doneAt=1478, sigs=6006, msgReceived=657, msgSent=160, KBytesSent=12171, KBytesReceived=14153}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=12499, doneAt=1471, sigs=6377, msgReceived=990, msgSent=372, KBytesSent=17380, KBytesReceived=18700}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=12999, doneAt=1594, sigs=6576, msgReceived=643, msgSent=565, KBytesSent=15511, KBytesReceived=15660}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=13499, doneAt=1590, sigs=6822, msgReceived=876, msgSent=185, KBytesSent=16653, KBytesReceived=19122}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=13999, doneAt=1303, sigs=7189, msgReceived=770, msgSent=156, KBytesSent=14603, KBytesReceived=16454}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=14499, doneAt=1565, sigs=7423, msgReceived=572, msgSent=266, KBytesSent=17001, KBytesReceived=17387}
cnt=25, sendPeriod=60 P2PSigNode{nodeId=14999, doneAt=1685, sigs=8101, msgReceived=737, msgSent=979, KBytesSent=21457, KBytesReceived=21435}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=499, doneAt=536, sigs=254, msgReceived=248, msgSent=167, KBytesSent=472, KBytesReceived=512}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=999, doneAt=838, sigs=590, msgReceived=337, msgSent=180, KBytesSent=1110, KBytesReceived=1107}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=1499, doneAt=758, sigs=760, msgReceived=361, msgSent=155, KBytesSent=1684, KBytesReceived=1794}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=1999, doneAt=878, sigs=1015, msgReceived=566, msgSent=437, KBytesSent=2919, KBytesReceived=3080}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=2499, doneAt=966, sigs=1358, msgReceived=353, msgSent=1023, KBytesSent=3137, KBytesReceived=2725}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=2999, doneAt=1112, sigs=1579, msgReceived=482, msgSent=259, KBytesSent=3660, KBytesReceived=3884}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=3499, doneAt=1074, sigs=1932, msgReceived=631, msgSent=307, KBytesSent=5214, KBytesReceived=5218}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=3999, doneAt=1093, sigs=2134, msgReceived=591, msgSent=434, KBytesSent=6129, KBytesReceived=6463}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=4499, doneAt=1201, sigs=2304, msgReceived=538, msgSent=230, KBytesSent=4706, KBytesReceived=4981}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=4999, doneAt=1427, sigs=3840, msgReceived=795, msgSent=2579, KBytesSent=8791, KBytesReceived=7426}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=5499, doneAt=1177, sigs=2821, msgReceived=498, msgSent=226, KBytesSent=6959, KBytesReceived=7288}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=5999, doneAt=1320, sigs=3126, msgReceived=560, msgSent=277, KBytesSent=7730, KBytesReceived=8022}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=6499, doneAt=1371, sigs=3269, msgReceived=550, msgSent=347, KBytesSent=8619, KBytesReceived=8995}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=6999, doneAt=1326, sigs=4000, msgReceived=638, msgSent=170, KBytesSent=9541, KBytesReceived=9523}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=7499, doneAt=1414, sigs=3884, msgReceived=616, msgSent=450, KBytesSent=9039, KBytesReceived=8927}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=7999, doneAt=1572, sigs=4026, msgReceived=774, msgSent=242, KBytesSent=10275, KBytesReceived=11393}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=8499, doneAt=1592, sigs=4606, msgReceived=681, msgSent=194, KBytesSent=12539, KBytesReceived=12583}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=8999, doneAt=1504, sigs=4533, msgReceived=586, msgSent=186, KBytesSent=8753, KBytesReceived=9427}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=9499, doneAt=1979, sigs=7233, msgReceived=938, msgSent=850, KBytesSent=13227, KBytesReceived=13593}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=9999, doneAt=1552, sigs=5039, msgReceived=901, msgSent=890, KBytesSent=14610, KBytesReceived=14436}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=10499, doneAt=1743, sigs=5678, msgReceived=981, msgSent=4006, KBytesSent=17735, KBytesReceived=11973}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=10999, doneAt=1504, sigs=5592, msgReceived=619, msgSent=258, KBytesSent=15681, KBytesReceived=16524}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=11499, doneAt=1629, sigs=5759, msgReceived=752, msgSent=248, KBytesSent=15277, KBytesReceived=16661}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=11999, doneAt=1823, sigs=6033, msgReceived=658, msgSent=161, KBytesSent=13047, KBytesReceived=14246}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=12499, doneAt=1841, sigs=7032, msgReceived=983, msgSent=429, KBytesSent=19533, KBytesReceived=19013}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=12999, doneAt=1839, sigs=6512, msgReceived=641, msgSent=562, KBytesSent=15670, KBytesReceived=15533}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=13499, doneAt=1825, sigs=6762, msgReceived=825, msgSent=182, KBytesSent=16844, KBytesReceived=18781}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=13999, doneAt=1506, sigs=7010, msgReceived=704, msgSent=154, KBytesSent=14571, KBytesReceived=16023}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=14499, doneAt=1858, sigs=7348, msgReceived=557, msgSent=311, KBytesSent=16584, KBytesReceived=17315}
cnt=25, sendPeriod=80 P2PSigNode{nodeId=14999, doneAt=1962, sigs=7538, msgReceived=734, msgSent=1088, KBytesSent=21219, KBytesReceived=20927}

 */
