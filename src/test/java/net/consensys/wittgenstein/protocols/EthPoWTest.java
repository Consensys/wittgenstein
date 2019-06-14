package net.consensys.wittgenstein.protocols;

import org.junit.Assert;
import org.junit.Test;

public class EthPoWTest {


  //@Test
  public void testDifficulty() {
    ETHPoW.POWBlock b = ETHPoW.POWBlock.createGenesis();
    long d = ETHPoW.POWBlock.calculateDifficulty(b, 9);
    Assert.assertEquals(949482177664138L, d);
  }
}
