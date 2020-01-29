package net.consensys.wittgenstein.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.utils.StatsHelper;

/**
 * Allows to run a scenario multiple time. The returned Stat object is an average of all the values.
 */
public class RunMultipleTimes<TP extends Protocol> {
  private final TP p;
  private final int runCount;
  private final int maxTime;
  private final List<StatsHelper.StatsGetter> statsGetters;
  private final Predicate<TP> finalCheck;

  /**
   * @param p - the protocol to execute
   * @param runCount - the number of type we should run this protocol
   * @param maxTime - the maximum execution time for a single execution, 0 for infinite time
   * @param statsGetters - the stats to gather at the end of an execution
   * @param finalCheck - a check to perform at the end of each execution, to validate the execution
   *     ran ok. Can be null.
   */
  public RunMultipleTimes(
      TP p,
      int runCount,
      int maxTime,
      List<StatsHelper.StatsGetter> statsGetters,
      Predicate<TP> finalCheck) {
    this.p = p;
    this.runCount = runCount;
    this.statsGetters = statsGetters;
    this.maxTime = maxTime;
    this.finalCheck = finalCheck;
  }

  public List<StatsHelper.Stat> run(Predicate<TP> contIf) {
    Map<StatsHelper.StatsGetter, List<StatsHelper.Stat>> allStats = new HashMap<>();

    for (int i = 0; i < runCount; i++) {
      @SuppressWarnings("unchecked")
      TP c = (TP) p.copy();
      c.network().rd.setSeed(i);
      c.init();
      boolean didSomething;
      do {
        try {
          didSomething = c.network().runMs(10);
        } catch (Throwable t) {
          throw new IllegalStateException(
              "Failed execution of "
                  + c
                  + " for random seed of "
                  + i
                  + ", time="
                  + c.network().time,
              t);
        }
      } while ((maxTime == 0 || c.network().time < maxTime)
          && (!didSomething || (contIf != null && contIf.test(c))));

      if (finalCheck != null && !finalCheck.test(c)) {
        throw new IllegalStateException("Failed execution of " + c + " for random seed of " + i);
      }

      for (StatsHelper.StatsGetter sg : statsGetters) {
        List<StatsHelper.Stat> res = allStats.computeIfAbsent(sg, (k) -> new ArrayList<>());
        StatsHelper.Stat s = sg.get(c.network().liveNodes());
        res.add(s);
      }
    }

    List<StatsHelper.Stat> res = new ArrayList<>();
    for (StatsHelper.StatsGetter sg : statsGetters) {
      List<StatsHelper.Stat> stats = allStats.get(sg);
      StatsHelper.Stat s = StatsHelper.avg(stats);
      res.add(s);
    }

    return res;
  }

  /** A simple continuation criteria where we check for the done status. */
  public static <TP extends Protocol> Predicate<TP> contUntilDone() {
    return p -> {
      for (Node n : p.network().liveNodes()) {
        if (n.doneAt == 0) {
          return true;
        }
      }
      return false;
    };
  }
}
