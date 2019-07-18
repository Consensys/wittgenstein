package net.consensys.wittgenstein.core;

import java.util.List;
import java.util.Random;
import net.consensys.wittgenstein.core.messages.FloodMessage;
import net.consensys.wittgenstein.core.messages.StatusFloodMessage;
import net.consensys.wittgenstein.core.utils.MoreMath;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class P2PNetworkTest {
  private int minPeers = 5;
  private P2PNetwork<P2PNodeTest> network = new P2PNetwork<>(minPeers, true);
  private NodeBuilder nb = new NodeBuilder();
  private P2PNodeTest n0 = new P2PNodeTest(network.rd, nb);
  private P2PNodeTest n1 = new P2PNodeTest(network.rd, nb);
  private P2PNodeTest n2 = new P2PNodeTest(network.rd, nb);
  private P2PNodeTest n3 = new P2PNodeTest(network.rd, nb);

  static class P2PNodeTest extends P2PNode<P2PNodeTest> {
    P2PNodeTest(Random rd, NodeBuilder nb) {
      super(rd, nb);
    }
  }

  @Before
  public void before() {
    network.setNetworkLatency(new NetworkLatency.NetworkNoLatency());
    network.addNode(n0);
    network.addNode(n1);
    network.addNode(n2);
    network.addNode(n3);
    for (int i = 0; i < 100; i++) {
      network.addNode(new P2PNodeTest(network.rd, nb));
    }
    network.setPeers();
  }

  @Test
  public void testMinimumPeers() {
    for (P2PNodeTest n : network.allNodes) {
      Assert.assertTrue(n.peers.size() >= minPeers);
    }
  }

  @Test
  public void testFloodMessageTest() {
    FloodMessage<P2PNodeTest> m = new FloodMessage<>(1, 0, 0);
    network.sendPeers(m, n0);
    Assert.assertEquals(1, n0.getMsgReceived(m.msgId()).size());

    network.runMs(2);
    int nodeCt = 0;
    // message is sent at time 1 and received at time 2 as there is no delay nor latency
    for (P2PNodeTest n : network.allNodes) {
      if (n == n0 || n0.peers.contains(n)) {
        Assert.assertEquals(1, n.getMsgReceived(m.msgId()).size());
        nodeCt++;
      } else {
        Assert.assertEquals(0, n.getMsgReceived(m.msgId()).size());
      }
    }

    for (int i = 0;
        i < MoreMath.log2(network.allNodes.size()) + 1 && nodeCt < network.allNodes.size();
        i++) {
      network.runMs(2);
      int nodeCt2 = count(m);
      Assert.assertTrue(nodeCt2 > nodeCt);
      nodeCt = nodeCt2;
    }

    Assert.assertEquals(network.allNodes.size(), nodeCt);
  }

  private int count(FloodMessage<P2PNodeTest> m) {
    int nodeCt = 0;
    // First message is sent at time 1 and received at time 2 as there no latency
    for (P2PNodeTest n : network.allNodes) {
      int size = n.getMsgReceived(m.msgId()).size();
      Assert.assertTrue(n + ", size=" + size, size == 0 || size == 1);
      if (size == 1) {
        nodeCt++;
      }
    }
    return nodeCt;
  }

  @Test
  public void testFloodMessageTestWithDelay() {
    FloodMessage<P2PNodeTest> m = new FloodMessage<>(1, 10, 15);
    network.sendPeers(m, n0);
    Assert.assertEquals(1, n0.getMsgReceived(m.msgId()).size());
    Assert.assertEquals(1, count(m));

    // Sent at (arrive 1ms later)
    // n0  : 11,     27,         43
    // n0p1:     23,        39
    // n0p2:                39
    // p1p1:            35
    network.runMs(11);
    Assert.assertEquals(1, count(m));

    network.runMs(1);
    Assert.assertEquals(2, count(m));

    Assert.assertEquals(12, network.time);
    network.runMs(11);
    Assert.assertEquals(2, count(m));

    Assert.assertEquals(23, network.time);
    network.runMs(1);
    Assert.assertEquals(3, count(m));

    Assert.assertEquals(24, network.time);
    network.runMs(3);
    Assert.assertEquals(3, count(m));

    Assert.assertEquals(27, network.time);
    network.runMs(1);
    Assert.assertEquals(4, count(m));

    network.runMs(7);
    Assert.assertEquals(35, network.time);
    Assert.assertEquals(4, count(m));
    network.runMs(1);
    Assert.assertEquals(5, count(m));

    network.run(1);
    Assert.assertEquals(network.allNodes.size(), count(m));
  }

  @Test
  public void testStatusFloodMessageTest() {
    StatusFloodMessage<P2PNodeTest> m = new StatusFloodMessage<>(1, 1, 1, 0, 0);
    network.sendPeers(m, n0);
    network.run(1);
    Assert.assertEquals(network.allNodes.size(), count(m));

    StatusFloodMessage<P2PNodeTest> mv2 = new StatusFloodMessage<>(1, 2, 1, 0, 0);
    network.sendPeers(mv2, n0);
    Assert.assertEquals(mv2, n0.getMsgReceived(m.msgId()).iterator().next());
    Assert.assertEquals(m, n1.getMsgReceived(m.msgId()).iterator().next());
    network.run(1);
    Assert.assertEquals(network.allNodes.size(), count(mv2));
    Assert.assertEquals(mv2, n0.getMsgReceived(m.msgId()).iterator().next());
    Assert.assertEquals(mv2, n1.getMsgReceived(m.msgId()).iterator().next());

    StatusFloodMessage<P2PNodeTest> m2 = new StatusFloodMessage<>(2, 1, 1, 0, 0);
    network.sendPeers(m2, n0);
    network.run(1);
    Assert.assertEquals(network.allNodes.size(), count(m2));
    Assert.assertEquals(network.allNodes.size(), count(m));
    Assert.assertEquals(mv2, n0.getMsgReceived(m.msgId()).iterator().next());
    Assert.assertEquals(mv2, n1.getMsgReceived(m.msgId()).iterator().next());
    Assert.assertEquals(m2, n0.getMsgReceived(m2.msgId()).iterator().next());
    Assert.assertEquals(m2, n1.getMsgReceived(m2.msgId()).iterator().next());
  }

  @Test
  public void testPeerRemoval() {
    final List<P2PNodeTest> n0Peers = n0.peers;
    int n0P = n0Peers.size();
    P2PNodeTest n0RemovedPeers = n0Peers.get(1);
    network.removeLink(n0, n0RemovedPeers);
    Assert.assertFalse(n0.peers.contains(n0RemovedPeers));
    Assert.assertFalse(n0RemovedPeers.peers.contains(n0));
    Assert.assertEquals((n0P - 1), n0.peers.size());
  }
}
