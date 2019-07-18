package net.consensys.wittgenstein.core;

import java.util.List;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import org.junit.Assert;
import org.junit.Test;

public class StatsTest {

  @Test
  public void testAvg() {
    StatsHelper.SimpleStats s1 = new StatsHelper.SimpleStats(10, 20, 30);
    StatsHelper.SimpleStats s2 = new StatsHelper.SimpleStats(16, 26, 36);

    StatsHelper.Stat avg = StatsHelper.avg(List.of(s1, s2));
    Assert.assertTrue(avg instanceof StatsHelper.SimpleStats);

    StatsHelper.SimpleStats avg2 = (StatsHelper.SimpleStats) avg;
    Assert.assertEquals(13, avg2.min);
    Assert.assertEquals(23, avg2.max);
    Assert.assertEquals(33, avg2.avg);
  }
}
