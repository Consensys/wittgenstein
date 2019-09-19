package net.consensys.wittgenstein.protocols.ethpow;

import org.junit.Assert;
import org.junit.Test;

public class ETHMinerAgentTest {

  @Test
  public void basicTest() {
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.4);
    p.init();
    p.network.run(200);

    ETHMinerAgent n = p.getByzNode();

    Assert.assertTrue(n.head.height > p.genesis.height);
    Assert.assertTrue(n.otherMinersHead.height > p.genesis.height);
    Assert.assertNotSame(n.otherMinersHead.producer, n);
    Assert.assertTrue(n.decisionNeeded);

    int size = n.minedToSend.size();
    Assert.assertTrue(size > 2);

    n.sendMinedBlocks(2);
    Assert.assertEquals(size - 2, n.minedToSend.size());
  }
}
