package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.utils.GeneralizedParetoDistribution;
import org.junit.Assert;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;

public class TestNetworkLatency {
  private final AtomicInteger ai = new AtomicInteger(1);
  private final Node.NodeBuilder nb = new Node.NodeBuilder() {
    public int getX() {
      return ai.getAndAdd(Node.MAX_X / 2);
    }
  };
  private final Node n1 = new Node(nb);
  private final Node n2 = new Node(nb);

  @Test
  public void testZeroDist() {
    Assert.assertEquals(0, n1.dist(n1));
    Assert.assertEquals(0, n2.dist(n2));
  }

  //@Test
  public void testGPD() {
    GeneralizedParetoDistribution gpd = new GeneralizedParetoDistribution(1.4, -0.3, 0.35);
    for (int i = 0; i < 100; i++) {
      System.out.println(i + " " + gpd.inverseF(i / 100.0));
    }
  }

  @Test
  public void testEstimateLatency() {
    NetworkLatency nl = new NetworkLatency.EthScanNetworkLatency();
    Network<Node> network = new Network<>();
    network.setNetworkLatency(nl);
    Node.NodeBuilder nb = new Node.NodeBuilder();
    for (int i = 0; i < 1000; i++) {
      network.addNode(new Node(nb));
    }

    NetworkLatency.MeasuredNetworkLatency m1 = NetworkLatency.estimateLatency(network, 100000);
    network.setNetworkLatency(m1);
    NetworkLatency.MeasuredNetworkLatency m2 = NetworkLatency.estimateLatency(network, 100000);
    for (int i = 0; i < m1.longDistrib.length; i++) {
      Assert.assertEquals(m1.longDistrib[i], m2.longDistrib[i]);
    }
  }
}
