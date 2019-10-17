package net.consensys.wittgenstein.protocols.ethpow;

import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class ETHMinerAgentTest {

  @Test
  public void basicTest() {
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.6, 1);
    p.init();
    p.network.run(200);

    ETHMinerAgent n = p.getByzNode();

    Assert.assertTrue(n.head.height > p.genesis.height);
    Assert.assertTrue(n.otherMinersHead.height > p.genesis.height);
    Assert.assertNotSame(n.otherMinersHead.producer, n);
  }

  @Test
  public void testSteps() {
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.4, 0);
    p.init();
    p.network.runH(2);

    ETHMinerAgent n = p.getByzNode();
    ETHPoW.POWBlock base = n.head;
    for (int i = 0; i < 100; i++) {
      Assert.assertTrue(n.goNextStep() > 0);
    }
    Set<ETHPoW.POWBlock> b = n.blocksReceivedByHeight.get(n.head.height);
    for (ETHPoW.POWBlock block : b) {
      System.out.println("Block received at height: " + block.height);
      System.out.println("Number oof blocks mined: " + (n.head.height - p.genesis.height));
    }
  }
}
