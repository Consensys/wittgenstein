package net.consensys.wittgenstein.core;

import org.junit.Assert;
import org.junit.Test;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkLatencyTest {
  private final AtomicInteger ai = new AtomicInteger(1);
  private final Node.NodeBuilder nb = new Node.NodeBuilder() {
    @Override
    public int getX(Random rd) {
      return ai.getAndAdd(Node.MAX_X / 2);
    }
  };
  private final Node n1 = new Node(new Random(0), nb);
  private final Node n2 = new Node(new Random(0), nb);

  @Test
  public void testZeroDist() {
    Assert.assertEquals(0, n1.dist(n1));
    Assert.assertEquals(0, n2.dist(n2));
  }

  @Test
  public void testIC3NetworkLatency() {
    NetworkLatency nl = new NetworkLatency.IC3NetworkLatency();

    Node a0 = new Node(new Random(0), new Node.NodeBuilder());
    Assert.assertEquals(NetworkLatency.IC3NetworkLatency.S10, nl.getLatency(a0, a0, 0));

    Node.NodeBuilder nb = new Node.NodeBuilder() {
      @Override
      public int getX(Random rd) {
        return Node.MAX_X / 2;
      }

      @Override
      public int getY(Random rd) {
        return Node.MAX_Y / 2;
      }
    };
    Node a1 = new Node(new Random(0), nb);
    Assert.assertEquals(NetworkLatency.IC3NetworkLatency.SW, nl.getLatency(a0, a1, 0));
    Assert.assertEquals(NetworkLatency.IC3NetworkLatency.SW, nl.getLatency(a1, a0, 0));
  }

  @Test
  public void testEstimateLatency() {
    NetworkLatency nl = new NetworkLatency.EthScanNetworkLatency();
    Network<Node> network = new Network<>();
    network.setNetworkLatency(nl);
    Node.NodeBuilder nb = new Node.NodeBuilder();
    for (int i = 0; i < 1000; i++) {
      network.addNode(new Node(new Random(0), nb));
    }

    NetworkLatency.MeasuredNetworkLatency m1 = NetworkLatency.estimateLatency(network, 100000);
    network.setNetworkLatency(m1);
    NetworkLatency.MeasuredNetworkLatency m2 = NetworkLatency.estimateLatency(network, 100000);
    for (int i = 0; i < m1.longDistrib.length; i++) {
      Assert.assertEquals(m1.longDistrib[i], m2.longDistrib[i]);
    }
  }
}
