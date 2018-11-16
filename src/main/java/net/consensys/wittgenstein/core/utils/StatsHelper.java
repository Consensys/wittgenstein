package net.consensys.wittgenstein.core.utils;


import net.consensys.wittgenstein.core.Node;
import java.util.List;

public class StatsHelper {

  public interface Get {
    long get(Node n);
  }

  public interface Stat {
    List<String> fields();

    long get(String fieldName);
  }

  public static class Counter implements Stat {
    final long val;

    public Counter(long val) {
      this.val = val;
    }

    @Override
    public List<String> fields() {
      return List.of("count");
    }

    @Override
    public long get(String fieldName) {
      return val;
    }

    @Override public String toString() {
      return "Counter{" + "val=" + val + '}';
    }
  }

  public static class SimpleStats implements Stat {
    public final long min;
    public final long max;
    public final long avg;

    public SimpleStats(long min, long max, long avg) {
      this.min = min;
      this.max = max;
      this.avg = avg;
    }

    public String toString() {
      return "min: " + min + ", max:" + max + ", avg:" + avg;
    }

    @Override
    public List<String> fields() {
      return List.of("min", "max", "avg");
    }

    @Override
    public long get(String fieldName) {
      switch (fieldName) {
        case "min":
          return min;
        case "max":
          return max;
        case "avg":
          return avg;
      }
      throw new IllegalStateException(fieldName);
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

  public interface StatsGetter {
    List<String> fields();

    Stat get(List<? extends Node> liveNodes);
  }

}
