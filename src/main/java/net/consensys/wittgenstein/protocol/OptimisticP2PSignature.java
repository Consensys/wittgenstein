package net.consensys.wittgenstein.protocol;


import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.P2PNetwork;
import net.consensys.wittgenstein.core.P2PNode;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/**
 * The simplest protocol: just send the signatures, and do an aggregation at the end.
 * <p>
 * Protocol: forward the message to all your peers if you have not done it already.
 *
 * That's the optimistic case. If the aggregated signature fails, the agregator needs
 *  to test the sub bases to find the culprit: that's a log N time.
 *  For 1000 nodes there would be a total of 2 + 10 * 2 + 2 pairing (~50 ms)
 *  Of course, if more nodes are byzantine then it will take longer, the worse case being 2*N pairings if 49% of the
 *   nodes are byzantine.
 *
 * <p>
 * Sends a lot of message (20K per node) so uses a lot of memory. We can't try more than 1K nodes
 * <p>
 * P2PSigNode{nodeId=999, doneAt=218, sigs=501, msgReceived=19465, msgSent=20041, KBytesSent=1017, KBytesReceived=988}
 * P2PSigNode{nodeId=999, doneAt=314, sigs=1000, msgReceived=39049, msgSent=40001, KBytesSent=2031, KBytesReceived=1982}
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
                Set<Node> dest = new HashSet<>(peers);
                dest.remove(from);
                network.send(ss, network.time + 1, this, dest);

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
        long[] distribVal = {12, 15, 19, 32, 35, 37, 40, 42, 45, 87, 155, 160, 185, 297, 1200};

        OptimisticP2PSignature p2ps = new OptimisticP2PSignature(100, 51,
                25, 3);
        p2ps.network.setNetworkLatency(distribProp, distribVal).setMsgDiscardTime(1000);
        //p2ps.network.removeNetworkLatency();
        P2PSigNode observer = p2ps.init();
        p2ps.network.run(5);
        System.out.println(observer);
    }

}
