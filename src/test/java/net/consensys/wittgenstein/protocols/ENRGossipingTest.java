package net.consensys.wittgenstein.protocols;


import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.*;
import org.junit.Assert;
import org.junit.Test;

public class ENRGossipingTest {


  // test: runs until all nodes have found at least 1 peer with a matching capability.

  //Test that copy method works
  @Test
  public void testCopy() {
    ENRGossiping p1 = new ENRGossiping(100, 0, 25, 10, 2, 5, 10);
    ENRGossiping p2 = p1.copy();
    p1.init();
    p1.network().run(10);
    p2.init();
    p2.network().run(10);

    for (ENRGossiping.ETHNode n1 : p1.network().allNodes) {
      ENRGossiping.ETHNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
      Assert.assertEquals(n1.down, n2.down);
      Assert.assertEquals(n1.getMsgReceived(-1).size(), n2.getMsgReceived(-1).size());
      Assert.assertEquals(n1.x, n2.x);
      Assert.assertEquals(n1.y, n2.y);
      Assert.assertEquals(n1.peers, n2.peers);
    }

  }

  @Test
  public void testLongRunning() {
    ENRGossiping p1 = new ENRGossiping(100, 0, 25, 10, 2, 5, 10);
    Message<ENRGossiping.ETHNode> act = new Message<>() {

      @Override
      public void action(Network<ENRGossiping.ETHNode> network, ENRGossiping.ETHNode from,
          ENRGossiping.ETHNode to) {

      }

    };
    while (p1.network().time < 100_000_000) {
      p1.network().runMs(1_000_000);
      p1.network().send(act, p1.network().getNodeById(1), p1.network().getNodeById(2));
    }
  }


}
