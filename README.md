# Wittgenstein
A simulator for some PoS, consensus or just distributed algorithms. Includes dfinity, Ethereum's Casper IMD, San Fermin and others.


## Why this name?
Wittgenstein was a concert pianist. He commissioned Ravel's Piano Concerto for the Left Hand, but changed it:
 'lines taken from the orchestral part and added to the solo, harmonies changed, parts added, bars cut and
  at the end a newly created series of great swirling arpeggios in the final cadenza.'

As it's often what happens with mock protocol implementations, it looked like the right name.


## How to build it
To check everything is correct:

gradle clean test

You can build a jar with this maven command:

gradle clean shadowJar

## How to run it
Once built:

java -Xms6000m -Xmx12048m -classpath build/libs/wittgenstein-all.jar net.consensys.wittgenstein.protocol.OptimisticP2PSignature

But you're actually supposed to write code to implement your specific scenarios today. An obvious improvement
 would be to be able to define scenarios reusable between protocols.

## How to implement a new protocol
Here is an example:
```java
public class PingPong {
    /**
     * You need a network. Nodes are added to this network.
     * Network latency can be set later.
     */
    private final Network<PingPongNode> network = new Network<>();

    /**
     * Nodes have positions. This position is chosen by the builder.
     */
    private final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition(network.rd);

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
```
This will print:
```
0 ms, pongs received 0
100 ms, pongs received 38
200 ms, pongs received 184
300 ms, pongs received 420
400 ms, pongs received 765
500 ms, pongs received 969
600 ms, pongs received 998
700 ms, pongs received 1000
800 ms, pongs received 1000
900 ms, pongs received 1000
```
