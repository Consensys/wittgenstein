package net.consensys.wittgenstein.protocols.ethpow;

import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class ETHMinerAgentTest {

  @Test
  public void basicTest() {
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.4, 1);
    p.init();
    p.network.run(200);

    ETHMinerAgent n = p.getByzNode();

    Assert.assertTrue(n.head.height > p.genesis.height);
    Assert.assertTrue(n.otherMinersHead.height > p.genesis.height);
    Assert.assertNotSame(n.otherMinersHead.producer, n);
    Assert.assertTrue(n.decisionNeeded);

    int size = n.minedToSend.size();
    Assert.assertTrue("size=" + size, size > 2);

    n.sendMinedBlocks(2);
    Assert.assertEquals(size - 2, n.minedToSend.size());
  }

  @Test
  public void testSteps() {
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.4, 0);
    p.init();
    p.network.runH(1);

    ETHMinerAgent n = p.getByzNode();
    ETHPoW.POWBlock base = n.head;
    for (int i = 0; i < 100; i++) {
      Assert.assertTrue(n.goNextStep());
    }
    Set<ETHPoW.POWBlock> b = n.blocksReceivedByHeight.get(n.head.height);
    for (ETHPoW.POWBlock block : b) {
      System.out.println("Block received at height: " + block.height);
    }
  }
}
