package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;
import org.junit.Assert;
import org.junit.Test;

public class P2PFloodTest {


  @Test
  public void testSimpleRun() {
    NetworkLatency nl = new NetworkLatency.NetworkNoLatency();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

    P2PFlood p = new P2PFlood(100, 10, 50, 1, 10, 30, nb, nl);
    p.network().run(10);

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
