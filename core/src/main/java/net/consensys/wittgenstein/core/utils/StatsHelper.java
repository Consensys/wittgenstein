package net.consensys.wittgenstein.core.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.consensys.wittgenstein.core.Node;

public class StatsHelper {
  private StatsHelper() {}

  public interface Get {
    long get(Node n);
  }

  public static class GetDoneAt implements Get {
    @Override
    public long get(Node n) {
      return n.doneAt;
    }
  }

  public interface Stat extends Cloneable {
    List<String> fields();

    long get(String fieldName);

    Stat createFromValue(Map<String, AtomicLong> vals);
  }

  /** Calculates the avg of a set of stats, field by field. */
  public static Stat avg(List<Stat> stats) {
    if (stats.isEmpty()) {
      throw new IllegalStateException();
    }
    if (stats.size() == 1) {
      return stats.get(0);
    }

    Map<String, AtomicLong> vals = new HashMap<>();
    for (String f : stats.get(0).fields()) {
      for (Stat s : stats) {
        AtomicLong al = vals.computeIfAbsent(f, (k) -> new AtomicLong(0));
        al.addAndGet(s.get(f));
      }
    }

    for (AtomicLong al : vals.values()) {
      al.set(al.get() / stats.size());
    }

    return stats.get(0).createFromValue(vals);
  }

  public static class Counter implements Stat {
    final long count;

    public Counter(long val) {
      this.count = val;
    }

    @Override
    public List<String> fields() {
      return List.of("count");
    }

    @Override
    public long get(String fieldName) {
      return count;
    }

    @Override
    public Stat createFromValue(Map<String, AtomicLong> vals) {
      return new Counter(vals.get(fields().get(0)).get());
    }

    @Override
    public String toString() {
      return "Counter{" + "count=" + count + '}';
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
      throw new IllegalStateException("field name not known in stats:" + fieldName);
    }

    @Override
    public Stat createFromValue(Map<String, AtomicLong> vals) {
      return new SimpleStats(vals.get("min").get(), vals.get("max").get(), vals.get("avg").get());
    }
  }

  public static SimpleStats getStatsOn(List<? extends Node> nodes, Get get) {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    long tot = 0;
    for (Node n : nodes) {
      long val = get.get(n);

      tot += val;
      if (val < min) min = val;
      if (val > max) max = val;
    }
    return new SimpleStats(min, max, (tot / nodes.size()));
  }

  public static SimpleStats getDoneAt(List<? extends Node> nodes) {
    return getStatsOn(nodes, n -> n.doneAt);
  }

  public static SimpleStats getMsgReceived(List<? extends Node> nodes) {
    return getStatsOn(nodes, Node::getMsgReceived);
  }

  public interface StatsGetter {
    List<String> fields();

    Stat get(List<? extends Node> liveNodes);
  }

  public abstract static class SimpleStatsGetter implements StatsGetter {
    private final List<String> fields = new SimpleStats(0, 0, 0).fields();

    public List<String> fields() {
      return fields;
    }
  }

  public static class DoneAtStatGetter extends SimpleStatsGetter {
    @Override
    public Stat get(List<? extends Node> liveNodes) {
      return getDoneAt(liveNodes);
    }
  }

  public static class MsgReceivedStatGetter extends SimpleStatsGetter {
    @Override
    public Stat get(List<? extends Node> liveNodes) {
      return getMsgReceived(liveNodes);
    }
  }
}
