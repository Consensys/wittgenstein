package net.consensys.wittgenstein.core.utils;


import net.consensys.wittgenstein.core.Node;
import java.util.List;

public class StatsHelper {

  public interface Get {
    long get(Node n);
  }


  public static class SimpleStats {
    public final long min;
    public final long max;
    public final long avg;

    SimpleStats(long min, long max, long avg) {
      this.min = min;
      this.max = max;
      this.avg = avg;
    }

    public String toString() {
      return "min: " + min + ", max:" + max + ", avg:" + avg;
    }
  }

  public static SimpleStats getStatsOn(List<? extends Node> nodes, Get get) {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
      long tot = 0;
    for (Node n : nodes) {
      long val = get.get(n);

      tot += val;
      if (val < min)
        min = val;
      if (val > max)
        max = val;
    }
    return new SimpleStats(min, max, (tot / nodes.size()));
  }


}
