package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.Protocol;
import org.junit.Assert;
import org.junit.Test;

public class P2PFloodTest {

  @Test
  public void testSimpleRun() {
    NetworkLatency nl = new NetworkLatency.NetworkNoLatency();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

    P2PFlood po = new P2PFlood(100, 10, 50, 1, 10, 30, nb, nl);
    Protocol p = po.copy();
    p.init();
    p.network().run(20);
    po.init();

    Assert.assertEquals(100, p.network().allNodes.size());
    for (Node nn : p.network().allNodes) {
      P2PFlood.P2PFloodNode n = (P2PFlood.P2PFloodNode) nn;
      if (n.down) {
        Assert.assertEquals(0, n.received.size());
      } else {
        Assert.assertEquals(1, n.received.size());
      }
    }
  }

  @Test
  public void testLongRun() {
    NetworkLatency nl = new NetworkLatency.AwsRegionNetworkLatency();
    NodeBuilder nb =
        new NodeBuilder.NodeBuilderWithCity(NetworkLatency.AwsRegionNetworkLatency.cities());

    Protocol po = new P2PFlood(4500, 4000, 500, 1, 50, 300, nb, nl);
    Protocol p = po.copy();
    p.init();
    p.network().run(2000);
    po.init();

    Assert.assertEquals(4500, p.network().allNodes.size());
    for (Node nn : p.network().allNodes) {
      P2PFlood.P2PFloodNode n = (P2PFlood.P2PFloodNode) nn;
      if (n.down) {
        Assert.assertEquals(0, n.received.size());
      } else {
        Assert.assertEquals(1, n.received.size());
      }
    }
  }
}
