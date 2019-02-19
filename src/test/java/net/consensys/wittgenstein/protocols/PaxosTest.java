package net.consensys.wittgenstein.protocols;

import org.junit.Assert;
import org.junit.Test;

public class PaxosTest {

  @Test
  public void testSimple() {
    Paxos p = new Paxos(new Paxos.PaxosParameters(3, 1, 1000, null, null));
    p.init();
    p.network().run(10);

    Assert.assertEquals(4, p.network().allNodes.size());
    Assert.assertEquals(2, p.majority);

    for (Paxos.ProposerNode n : p.proposers) {
      Assert.assertTrue(n.seqIP > 0);
    }
  }

  @Test
  public void testCopy() {
    Paxos p1 = new Paxos(new Paxos.PaxosParameters(3, 2, 1000, null, null));
    Paxos p2 = p1.copy();
    p1.init();
    p1.network().runMs(2000);
    p2.init();
    p2.network().runMs(2000);

    for (Paxos.PaxosNode n1 : p1.network().allNodes) {
      Paxos.PaxosNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.getMsgReceived(), n2.getMsgReceived());
    }
  }

  @Test
  public void testPlay() {
    Paxos p1 = new Paxos(new Paxos.PaxosParameters());
    p1.play();
  }
}
