package net.consensys.wittgenstein.protocol;


import net.consensys.wittgenstein.core.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The simplest protocol: just send the signatures, and do an aggregation at the end.
 * <p>
 * Protocol: forward the message to all your peers if you have not done it already.
 * <p>
 * That's the optimistic case. If the aggregated signature fails, the agregator needs
 * to test the sub bases to find the culprit: that's a log N time.
 * For 1000 nodes there would be a total of 2 + 10 * 2 + 2 pairing (~50 ms)
 * Of course, if more nodes are byzantine then it will take longer, the worse case being 2*N pairings if 49% of the
 * nodes are byzantine.
 *
 * <p>
 * Sends a lot of message (20K per node) so uses a lot of memory. We can't try more than 4K nodes
 * <p>
 *
 * NetworkLatencyByDistance{fix=10, max=200, spread=50%}
 * P2PSigNode{nodeId=999, doneAt=137, sigs=501, msgReceived=21200, msgSent=21023, KBytesSent=1067, KBytesReceived=1076}
 * P2PSigNode{nodeId=1999, doneAt=140, sigs=1001, msgReceived=59406, msgSent=59978, KBytesSent=3045, KBytesReceived=3016}
 * P2PSigNode{nodeId=2999, doneAt=140, sigs=1501, msgReceived=70113, msgSent=72049, KBytesSent=3658, KBytesReceived=3560}
 * P2PSigNode{nodeId=3999, doneAt=149, sigs=2001, msgReceived=114855, msgSent=118060, KBytesSent=5995, KBytesReceived=5832}
 * P2PSigNode{nodeId=4999, doneAt=150, sigs=2501, msgReceived=148088, msgSent=149994, KBytesSent=7616, KBytesReceived=7520}
 *
 * P2PSigNode{nodeId=999, doneAt=218, sigs=501, msgReceived=19465, msgSent=20041, KBytesSent=1017, KBytesReceived=988}
 * P2PSigNode{nodeId=999, doneAt=220, sigs=501, msgReceived=19501, msgSent=20041, KBytesSent=1017, KBytesReceived=990}
 * P2PSigNode{nodeId=999, doneAt=219, sigs=501, msgReceived=20478, msgSent=21012, KBytesSent=1067, KBytesReceived=1039}
 * P2PSigNode{nodeId=1499, doneAt=227, sigs=751, msgReceived=34754, msgSent=36007, KBytesSent=1828, KBytesReceived=1764}
 * P2PSigNode{nodeId=1999, doneAt=226, sigs=1001, msgReceived=56539, msgSent=59060, KBytesSent=2999, KBytesReceived=2871}
 * P2PSigNode{nodeId=2499, doneAt=240, sigs=1251, msgReceived=53712, msgSent=55045, KBytesSent=2795, KBytesReceived=2727}
 * P2PSigNode{nodeId=2999, doneAt=230, sigs=1501, msgReceived=68177, msgSent=72049, KBytesSent=3658, KBytesReceived=3462}
 * P2PSigNode{nodeId=3999, doneAt=244, sigs=2001, msgReceived=116390, msgSent=120060, KBytesSent=6096, KBytesReceived=5910}
 * P2PSigNode{nodeId=9999, doneAt=201, sigs=5001, msgReceived=273298, msgSent=285058, KBytesSent=14475, KBytesReceived=13878}
 */
@SuppressWarnings("WeakerAccess")
public class OptimisticP2PSignature {
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


    final P2PNetwork network;
    final Node.NodeBuilder nb;

    public OptimisticP2PSignature(int nodeCount, int threshold, int connectionCount, int pairingTime) {
        this.nodeCount = nodeCount;
        this.threshold = threshold;
        this.connectionCount = connectionCount;
        this.pairingTime = pairingTime;

        this.network = new P2PNetwork(connectionCount);
        this.nb = new Node.NodeBuilderWithPosition(network.rd);
    }

    static class SendSig extends Network.MessageContent<P2PSigNode> {
        final int sig;

        public SendSig(@NotNull P2PSigNode who) {
            this.sig = who.nodeId;
        }

        @Override
        public int size() {
            // NodeId + sig
            return 4 + 48;
        }

        @Override
        public void action(@NotNull P2PSigNode from, @NotNull P2PSigNode to) {
            to.onSig(from, this);
        }
    }


    public class P2PSigNode extends P2PNode {
        final BitSet verifiedSignatures = new BitSet(nodeCount);

        boolean done = false;
        long doneAt = 0;

        P2PSigNode() {
            super(nb);
        }

        void onSig(@NotNull P2PSigNode from, @NotNull SendSig ss) {
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
            return "P2PSigNode{" +
                    "nodeId=" + nodeId +
                    ", doneAt=" + doneAt +
                    ", sigs=" + verifiedSignatures.cardinality() +
                    ", msgReceived=" + msgReceived +
                    ", msgSent=" + msgSent +
                    ", KBytesSent=" + bytesSent / 1024 +
                    ", KBytesReceived=" + bytesReceived / 1024 +
                    '}';
        }
    }

    P2PSigNode init() {
        P2PSigNode last = null;
        for (int i = 0; i < nodeCount; i++) {
            final P2PSigNode n = new P2PSigNode();
            last = n;
            network.addNode(n);
            network.registerTask(() -> n.onSig(n, new SendSig(n)), 1, n);
        }

        network.setPeers();

        return last;
    }

    public static void main(String... args) {
        int[] distribProp = {1, 33, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
        int[] distribVal = {12, 15, 19, 32, 35, 37, 40, 42, 45, 87, 155, 160, 185, 297, 1200};
        for (int i = 0; i < distribVal.length; i++)
            distribVal[i] += 50; // more or less the latency we had before the refactoring

        NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
        System.out.println(""+nl);

        for (int i = 1000; i < 8000; i += 1000) {
            OptimisticP2PSignature p2ps = new OptimisticP2PSignature(i, i / 2 + 1, 25, 3);
            p2ps.network.setNetworkLatency(nl);
            P2PSigNode observer = p2ps.init();
            p2ps.network.run(5);
            System.out.println(observer);
        }
    }
}
