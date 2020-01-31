package net.consensys.wittgenstein.protocols;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;

/**
 * The simplest protocol to exchange signatures: just send the signatures, and do an aggregation at
 * the end.
 *
 * <p>Protocol: forward the message to all your peers if you have not done it already.
 *
 * <p>That's the optimistic case. If the aggregated signature fails, the aggregator needs to test
 * the sub bases to find the culprit: that's a log N time. For 1000 nodes there would be a total of
 * 2 + 10 * 2 + 2 pairing (~50 ms) Of course, if more nodes are byzantine then it will take longer,
 * the worse case being 2*N pairings if 49% of the nodes are byzantine.
 *
 * <p>Sends a lot of messages so uses a lot of memory and slow to test.
 *
 * <p>
 */
@SuppressWarnings("WeakerAccess")
public class OptimisticP2PSignature implements Protocol {

  OptimisticP2PSignatureParameters params;
  final P2PNetwork<P2PSigNode> network;
  final NodeBuilder nb;

  public static class OptimisticP2PSignatureParameters extends WParameters {
    /** The number of nodes in the network */
    final int nodeCount;

    /** The number of signatures to reach to finish the protocol. */
    final int threshold;

    /** The typical number of peers a peer has. It can be less (but at least 3) or more. */
    final int connectionCount;

    /** The time it takes to do a pairing for a node. */
    final int pairingTime;

    final String nodeBuilderName;
    final String networkLatencyName;

    // for json
    @SuppressWarnings("unused")
    public OptimisticP2PSignatureParameters() {
      this.nodeCount = 100;
      this.threshold = 99;
      this.connectionCount = 20;
      this.pairingTime = 1;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public OptimisticP2PSignatureParameters(
        int nodeCount,
        int threshold,
        int connectionCount,
        int pairingTime,
        String nodeBuilderName,
        String networkLatencyName) {
      this.nodeCount = nodeCount;
      this.threshold = threshold;
      this.connectionCount = connectionCount;
      this.pairingTime = pairingTime;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public OptimisticP2PSignature(OptimisticP2PSignatureParameters params) {
    this.params = params;
    this.network = new P2PNetwork<>(params.connectionCount, false);
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  public OptimisticP2PSignature copy() {
    return new OptimisticP2PSignature(params);
  }

  static class SendSig extends Message<P2PSigNode> {
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
    public void action(Network<P2PSigNode> network, P2PSigNode from, P2PSigNode to) {
      to.onSig(from, this);
    }
  }

  public class P2PSigNode extends P2PNode<P2PSigNode> {
    final BitSet verifiedSignatures = new BitSet(params.nodeCount);

    boolean done = false;

    P2PSigNode() {
      super(network.rd, nb);
    }

    void onSig(P2PSigNode from, SendSig ss) {
      if (!done && !verifiedSignatures.get(ss.sig)) {
        verifiedSignatures.set(ss.sig);

        // It's better to use a list than a set here, because it makes the simulation
        //  repeatable: there's no order in a set.
        List<P2PSigNode> dests = new ArrayList<>(peers.size() - 1);
        for (P2PSigNode n : peers) {
          if (n != from) {
            dests.add(n);
          }
        }
        network.send(ss, network.time + 1, this, dests);

        if (verifiedSignatures.cardinality() >= params.threshold) {
          done = true;
          doneAt = network.time + params.pairingTime * 2;
        }
      }
    }

    @Override
    public String toString() {
      return "P2PSigNode{"
          + "nodeId="
          + nodeId
          + ", doneAt="
          + doneAt
          + ", sigs="
          + verifiedSignatures.cardinality()
          + ", msgReceived="
          + msgReceived
          + ", msgSent="
          + msgSent
          + ", KBytesSent="
          + bytesSent / 1024
          + ", KBytesReceived="
          + bytesReceived / 1024
          + '}';
    }
  }

  @Override
  public void init() {
    for (int i = 0; i < params.nodeCount; i++) {
      final P2PSigNode n = new P2PSigNode();
      network.addNode(n);
      network.registerTask(() -> n.onSig(n, new SendSig(n)), 1, n);
    }

    network.setPeers();
  }

  @Override
  public Network<P2PSigNode> network() {
    return network;
  }

  public static void main(String... args) {
    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    System.out.println("" + nl);
    boolean printLat = false;

    OptimisticP2PSignature p2ps =
        new OptimisticP2PSignature(
            new OptimisticP2PSignatureParameters(1000, 1000 / 2 + 1, 13, 3, nb, nl));
    p2ps.init();
    P2PSigNode observer = p2ps.network.getNodeById(0);

    if (!printLat) {
      System.out.println("NON P2P " + NetworkLatency.estimateLatency(p2ps.network, 100000));
      System.out.println("\nP2P " + NetworkLatency.estimateP2PLatency(p2ps.network, 100000));
      printLat = true;
    }

    p2ps.network.run(5);
    System.out.println(observer);
  }
}
