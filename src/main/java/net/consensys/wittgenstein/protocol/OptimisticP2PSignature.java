package net.consensys.wittgenstein.protocol;


import net.consensys.wittgenstein.core.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The simplest protocol to exhange signatures: just send the signatures, and do an aggregation at
 * the end.
 * <p>
 * Protocol: forward the message to all your peers if you have not done it already.
 * <p>
 * That's the optimistic case. If the aggregated signature fails, the aggregator needs to test the
 * sub bases to find the culprit: that's a log N time. For 1000 nodes there would be a total of 2 +
 * 10 * 2 + 2 pairing (~50 ms) Of course, if more nodes are byzantine then it will take longer, the
 * worse case being 2*N pairings if 49% of the nodes are byzantine.
 *
 * <p>
 * Sends a lot of messages so uses a lot of memory and slow to test.
 * <p>
 */
@SuppressWarnings("WeakerAccess")
public class OptimisticP2PSignature implements Protocol {
  /**
   * The number of nodes in the network
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


  final P2PNetwork network;
  final Node.NodeBuilder nb;

  public OptimisticP2PSignature(int nodeCount, int threshold, int connectionCount,
      int pairingTime) {
    this.nodeCount = nodeCount;
    this.threshold = threshold;
    this.connectionCount = connectionCount;
    this.pairingTime = pairingTime;

    this.network = new P2PNetwork(connectionCount);
    this.nb = new Node.NodeBuilderWithRandomPosition();
  }

  public OptimisticP2PSignature copy() {
    return new OptimisticP2PSignature(nodeCount, threshold, connectionCount, pairingTime);
  }

  static class SendSig extends Network.Message<P2PSigNode> {
    final int sig;

    public SendSig(P2PSigNode who) {
      this.sig = who.nodeId;
    }

    @Override
    public int size() {
      // NodeId + sig
      return 4 + 48;
    }

    @Override
    public void action(P2PSigNode from, P2PSigNode to) {
      to.onSig(from, this);
    }
  }


  public class P2PSigNode extends P2PNode {
    final BitSet verifiedSignatures = new BitSet(nodeCount);

    boolean done = false;

    P2PSigNode() {
      super(network.rd, nb);
    }

    void onSig(P2PSigNode from, SendSig ss) {
      if (!done && !verifiedSignatures.get(ss.sig)) {
        verifiedSignatures.set(ss.sig);

        // It's better to use a list than a set here, because it makes the simulation
        //  repeatable: there's no order in a set.
        List<Node> dests = new ArrayList<>(peers.size() - 1);
        for (Node n : peers) {
          if (n != from) {
            dests.add(n);
          }
        }
        network.send(ss, network.time + 1, this, dests);

        if (verifiedSignatures.cardinality() >= threshold) {
          done = true;
          doneAt = network.time + pairingTime * 2;
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

  @Override
  public void init() {
    for (int i = 0; i < nodeCount; i++) {
      final P2PSigNode n = new P2PSigNode();
      network.addNode(n);
      network.registerTask(() -> n.onSig(n, new SendSig(n)), 1, n);
    }

    network.setPeers();
  }

  @Override
  public Network<?> network() {
    return network;
  }

  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    System.out.println("" + nl);
    boolean printLat = false;

    for (int i = 1000; i < 2000; i += 1000) {
      OptimisticP2PSignature p2ps = new OptimisticP2PSignature(i, i / 2 + 1, 13, 3);
      p2ps.network.setNetworkLatency(nl);
      p2ps.init();
      P2PSigNode observer = (P2PSigNode) p2ps.network.getNodeById(0);

      if (!printLat) {
        System.out.println("NON P2P " + NetworkLatency.estimateLatency(p2ps.network, 100000));
        System.out.println("\nP2P " + NetworkLatency.estimateP2PLatency(p2ps.network, 100000));
        printLat = true;
      }

      p2ps.network.run(5);
      System.out.println(observer);
    }
  }
}
