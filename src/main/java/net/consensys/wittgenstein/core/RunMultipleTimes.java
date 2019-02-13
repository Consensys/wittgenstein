package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Allows to run a scenario multiple time. The returned Stat object is an average of all the values.
 */
public class RunMultipleTimes {
  final private Protocol p;
  final private int runCount;
  final private List<StatsHelper.StatsGetter> statsGetters;

  public RunMultipleTimes(Protocol p, int runCount, List<StatsHelper.StatsGetter> statsGetters) {
    this.p = p;
    this.runCount = runCount;
    this.statsGetters = statsGetters;
  }

  public List<StatsHelper.Stat> run(Predicate<Protocol> contIf) {
    Map<StatsHelper.StatsGetter, List<StatsHelper.Stat>> allStats = new HashMap<>();

    for (int i = 0; i < runCount; i++) {
      Protocol c = p.copy();
      c.network().rd.setSeed(i);
      c.init();
      do {
        c.network().runMs(10);
      } while (contIf.test(c));

      for (StatsHelper.StatsGetter sg : statsGetters) {
        List<StatsHelper.Stat> res = allStats.computeIfAbsent(sg, (k) -> new ArrayList<>());
        StatsHelper.Stat s = sg.get(c.network().allNodes);
        //System.out.println(sg+" "+s);
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
}
