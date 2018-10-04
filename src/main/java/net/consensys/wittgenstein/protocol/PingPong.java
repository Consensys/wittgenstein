package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;

public class PingPong {
    /**
     * You need a network. Nodes are added to this network.
     * Network latency can be set later.
     */
    private final Network<PingPongNode> network = new Network<>();

    /**
     * Nodes have positions. This position is chosen by the builder.
     */
    private final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition(network.rd); //

    /**
     * Messages, exchanged on the network, are specific to the protocol.
     */
    static class Ping extends Network.Message<PingPongNode> {
        @Override
        public void action(PingPongNode from, PingPongNode to) {
            to.onPing(from);
        }
    }

    static class Pong extends Network.Message<PingPongNode> {
        @Override
        public void action(PingPongNode from, PingPongNode to) {
            to.onPong(from);
        }
    }

    /**
     * Nodes are specialized for the protocol.
     */
    class PingPongNode extends Node {
        int pong;

        PingPongNode() {
            super(nb);
        }

        void onPing(PingPongNode from) {
            network.send(new Pong(), this, from);
        }

        void onPong(PingPongNode from) {
            pong++;
        }
    }

    PingPongNode init(int nodeCt) {
        for (int i = 0; i < nodeCt; i++) {
            network.addNode(new PingPongNode());
        }
        return network.getNodeById(0);
    }


    public static void main(String... args) {
        PingPong p = new PingPong();

        // Set the latency.
        p.network.setNetworkLatency(new NetworkLatency.NetworkLatencyByDistance());

        PingPongNode witness = p.init(1000);
        p.network.sendAll(new Ping(), witness);
        for (int i = 0; i < 1000; i += 100) {
            System.out.println(i + " ms, pongs received " + witness.pong);
            p.network.runMs(100);
        }
    }
}
