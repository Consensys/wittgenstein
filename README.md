[![CircleCI](https://circleci.com/gh/ConsenSys/wittgenstein.svg?style=svg&circle-token=586dc7b883ee5381aa17247b8f058157e166b307)](https://circleci.com/gh/ConsenSys/wittgenstein)

# Wittgenstein
A simulator for some PoS, consensus or just distributed algorithms. Includes dfinity, Ethereum's Casper IMD, San Fermin and others. A somehow outdated presentation can be found [here](https://docs.google.com/presentation/d/1NQAm_zciVToOD3LpauKPxpkQ96WNh_3UMPMA483bHgM/edit?usp=sharing).

It allows you to test protocols, and to generate large graph and videos of the protocol in action, such as this one (generated for Handel: aggregation at scale with Byzantine nodes (click on the image to see the video): [![Watch the video](https://img.youtube.com/vi/reQTJF7EFLg/maxresdefault.jpg)](https://youtu.be/reQTJF7EFLg)


## Why this name?
Wittgenstein was a concert pianist. He commissioned Ravel's Piano Concerto for the Left Hand, but changed it:
 'lines taken from the orchestral part and added to the solo, harmonies changed, parts added, bars cut and
  at the end a newly created series of great swirling arpeggios in the final cadenza.'

As it's often what happens with mock protocol implementations, it looked like the right name.


## How to build it
You will need java 9+ and gradle installed.

To check everything is correct:
```
gradle clean test
```
You can build a jar with gradle:
```
gradle clean shadowJar
```
## How to run it
Once built:
```
java -Xms6000m -Xmx12048m -classpath protocols/build/libs/wittgenstein-all.jar net.consensys.wittgenstein.protocols.GSFSignature
```

This command is typically for a 16GB machine. The memory is very important when you want to simulate tens of thousands of nodes. If you have less memory, use lower values for -Xms and -Xmx, and run the simulations with less nodes.

But you're actually supposed to write code to implement your specific scenarios today. An obvious improvement
 would be to be able to define scenarios reusable between protocols.

## How to implement a new protocol

For a complete description go to the wiki.

Here is an example:
```java
public class PingPong implements Protocol {
  /**
   * You need a network. Nodes are added to this network. Network latency can be set later.
   */
  private final Network<PingPongNode> network = new Network<>();

  /**
   * Nodes have positions. This position is chosen by the builder.
   */
  private final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition();

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
      to.onPong();
    }
  }

 
  class PingPongNode extends Node {
    int pong;

    PingPongNode() {
      super(network.rd, nb);
    }

    void onPing(PingPongNode from) {
      network.send(new Pong(), this, from);
    }

    void onPong() {
      pong++;
    }
  }

  @Override
  public PingPong copy() {
    return new PingPong();
  }

  @Override
  public void init() {
    for (int i = 0; i < 1000; i++) {
      network.addNode(new PingPongNode());
    }
  }

  @Override
  public Network<PingPongNode> network() {
    return network;
  }


  public static void main(String... args) {
    PingPong p = new PingPong();
    
    p.network.setNetworkLatency(new NetworkLatency.NetworkLatencyByDistance());

    p.init();
    PingPongNode witness = p.network.getNodeById(0);
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
