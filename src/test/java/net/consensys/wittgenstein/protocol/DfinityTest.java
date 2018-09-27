package net.consensys.wittgenstein.protocol;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DfinityTest {
    private final Dfinity dfinity = new Dfinity(10,10,10,1,1,0);

    @Before
    public void before(){
        dfinity.network.removeNetworkLatency();
        dfinity.init();
    }

    @Test
    public void testRun(){
        dfinity.network.run(11);
        Assert.assertEquals(3, dfinity.network.observer.head.height);
    }
}
