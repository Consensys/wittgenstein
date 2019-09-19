package net.consensys.wittgenstein.protocols.ethpow;

import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

public class ETHMinerAgentTest {

  @Test
  public void basicTest() {
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.4);
    p.init();
    p.network.run(200);

    ETHMinerAgent n = p.getByzNode();

    Assert.assertTrue(n.head.height > p.genesis.height);
    Assert.assertTrue(n.otherHead.height > p.genesis.height);
    Assert.assertNotSame(n.otherHead.producer, n);
    Assert.assertTrue(n.actionNeeded);

    int size = n.minedToSend.size();
    Assert.assertTrue(size > 2);

    n.sendMinedBlocks(2);
    Assert.assertEquals(size - 2, n.minedToSend.size());
  }
  @Test
  public void stepsTest(){
    ETHMinerAgent.ETHPowWithAgent p = ETHMinerAgent.create(.4);
    p.init();
    ETHMinerAgent byz = p.getByzNode();
    Assert.assertTrue(byz.goNextStep());
    //Print stats
    for(int i =0; i<1000; i++) {
      Random r = new Random();
      int action = r.nextInt((4 - 0) + 1) + 0;
      byz.goNextStep();
      if((byz.minedToSend.size()>=3)){
        byz.sendMinedBlocks(3);
      }
      byz.sendMinedBlocks(action);
      System.out.println("Action: "+action);
      System.out.println("Secret Block Chain: " + byz.getSecretBlockSize());
      System.out.println("Time in seconds: " + p.getTimeInSeconds());
      System.out.println("Mined to Send" + byz.minedToSend);
      System.out.println("Advance or delay: "+byz.getAdvance());
    }
  }
}
