package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Test;

public class P2PFloodTest {

  @Test
  public void testSimpleRun() {
    String nl = NetworkLatency.NetworkNoLatency.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);

    P2PFlood po = new P2PFlood(new P2PFlood.P2PFloodParameters(100, 10, 50, 1, 1, 10, 30, nb, nl));
    Protocol p = po.copy();
    p.init();
    p.network().run(20);
    po.init();

    Assert.assertEquals(100, p.network().allNodes.size());
    for (Node nn : p.network().allNodes) {
      P2PFlood.P2PFloodNode n = (P2PFlood.P2PFloodNode) nn;
      if (n.isDown()) {
        Assert.assertEquals(0, n.getMsgReceived(-1).size());
      } else {
        Assert.assertEquals(1, n.getMsgReceived(-1).size());
      }
    }
  }

  @Test
  public void testLongRun() {
    String nl = NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.AWS, true, 0);

    Protocol po =
        new P2PFlood(new P2PFlood.P2PFloodParameters(4500, 4000, 500, 1, 1, 50, 300, nb, nl));
    Protocol p = po.copy();
    p.init();
    p.network().run(2000);
    po.init();

    Assert.assertEquals(4500, p.network().allNodes.size());
    for (Node nn : p.network().allNodes) {
      P2PFlood.P2PFloodNode n = (P2PFlood.P2PFloodNode) nn;
      if (n.isDown()) {
        Assert.assertEquals(0, n.getMsgReceived(-1).size());
      } else {
        Assert.assertEquals(1, n.getMsgReceived(-1).size());
      }
    }
  }

  @Test
  public void testCopy() {
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    P2PFlood p1 = new P2PFlood(new P2PFlood.P2PFloodParameters(2000, 10, 50, 1, 1, 10, 30, nb, nl));
    P2PFlood p2 = p1.copy();
    p1.init();
    p1.network().runMs(1000);
    p2.init();
    p2.network().runMs(1000);

    for (P2PFlood.P2PFloodNode n1 : p1.network().allNodes) {
      P2PFlood.P2PFloodNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
      Assert.assertEquals(n1.isDown(), n2.isDown());
      Assert.assertEquals(n1.getMsgReceived(-1).size(), n2.getMsgReceived(-1).size());
      Assert.assertEquals(n1.x, n2.x);
      Assert.assertEquals(n1.y, n2.y);
      Assert.assertEquals(n1.peers, n2.peers);
    }
  }
}
