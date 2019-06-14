package net.consensys.wittgenstein.protocols;

import org.junit.Assert;
import org.junit.Test;

public class EthPoWTest {


  @Test
  public void testDifficulty() {
    ETHPoW.POWBlock b1 = ETHPoW.POWBlock.createGenesis();
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(null, b1, b1.proposalTime + 13);
    Assert.assertEquals(1949482177664138L, b2.difficulty);

    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(null, b2, b2.proposalTime + 7);
    Assert.assertEquals(1950434207476428L, b3.difficulty);

    ETHPoW.POWBlock b4 = new ETHPoW.POWBlock(null, b3, b3.proposalTime + 4);
    Assert.assertEquals(1951386702147025L, b4.difficulty);

    ETHPoW.POWBlock b5 = new ETHPoW.POWBlock(null, b4, b4.proposalTime + 39);
    Assert.assertEquals(1948528359750282L, b5.difficulty);
  }
}
