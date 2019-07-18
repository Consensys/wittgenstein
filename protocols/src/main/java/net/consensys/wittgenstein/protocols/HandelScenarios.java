package net.consensys.wittgenstein.protocols;

import static java.util.Map.entry;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import net.consensys.wittgenstein.core.RunMultipleTimes;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.CSVFormatter;
import net.consensys.wittgenstein.tools.Graph;
import net.consensys.wittgenstein.tools.NodeDrawer;

public class HandelScenarios {

  static class BasicStats {
    final int doneAtMin;
    final int doneAtAvg;
    final int doneAtMax;

    final int msgRcvMin;
    final int msgRcvAvg;
    final int msgRcvMax;

    BasicStats(
        long doneAtMin,
        long doneAtAvg,
        long doneAtMax,
        long msgRcvMin,
        long msgRcvAvg,
        long msgRcvMax) {
      this.doneAtMin = (int) doneAtMin;
      this.doneAtAvg = (int) doneAtAvg;
      this.doneAtMax = (int) doneAtMax;
      this.msgRcvMin = (int) msgRcvMin;
      this.msgRcvAvg = (int) msgRcvAvg;
      this.msgRcvMax = (int) msgRcvMax;
    }

    @Override
    public String toString() {
      return "; doneAtAvg=" + doneAtAvg + "; msgRcvAvg=" + msgRcvAvg;
    }
  }

  private Handel.HandelParameters defaultParams() {
    return defaultParams(null, null, null, null, null, null, null, null, null);
  }

  private Handel.HandelParameters defaultParams(
      Integer nodes,
      Double deadRatio,
      Double tor,
      Integer periodTime,
      Integer desynchronizedStart,
      Boolean byzantineSuicide,
      Boolean hiddenByzantine,
      String bestLevelFunction,
      RegistryNodeBuilders.Location loc) {
    nodes = nodes != null ? nodes : 2048;
    deadRatio = deadRatio != null ? deadRatio : 0.10;
    tor = tor != null ? tor : 0;
    periodTime = periodTime != null ? periodTime : 20;
    desynchronizedStart = desynchronizedStart != null ? desynchronizedStart : 0;
    hiddenByzantine = hiddenByzantine != null ? hiddenByzantine : false;
    byzantineSuicide = byzantineSuicide != null ? byzantineSuicide : false;
    loc = loc == null ? RegistryNodeBuilders.Location.AWS : loc;
    String lat =
        loc == RegistryNodeBuilders.Location.AWS
            ? NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName()
            : loc == RegistryNodeBuilders.Location.CITIES
                ? NetworkLatency.NetworkLatencyByCity.class.getSimpleName()
                : NetworkLatency.NetworkLatencyByDistance.class.getSimpleName();

    double treshold = (1.0 - (deadRatio + 0.01));

    Handel.HandelParameters p =
        new Handel.HandelParameters(
            nodes,
            (int) (nodes * treshold),
            4,
            50,
            periodTime,
            10,
            (int) (nodes * deadRatio),
            RegistryNodeBuilders.name(loc, false, tor),
            lat,
            desynchronizedStart,
            byzantineSuicide,
            hiddenByzantine,
            bestLevelFunction,
            null);

    p.window = new Handel.WindowParameters(16, 1, 128, new Handel.CongestionExp(2, 4), true);
    return p;
  }

  private BasicStats run(int rounds, Handel.HandelParameters params) {
    List<StatsHelper.StatsGetter> stats =
        List.of(new StatsHelper.DoneAtStatGetter(), new StatsHelper.MsgReceivedStatGetter());
    RunMultipleTimes<Handel> rmt =
        new RunMultipleTimes<>(new Handel(params), rounds, 0, stats, null);
    List<StatsHelper.Stat> res = rmt.run(Handel.newContIf());

    return new BasicStats(
        res.get(0).get("min"),
        res.get(0).get("avg"),
        res.get(0).get("max"),
        res.get(1).get("min"),
        res.get(1).get("avg"),
        res.get(1).get("max"));
  }

  private void runOnce(Handel.HandelParameters params, String fileName) {
    Handel p = new Handel(params);
    Predicate<Handel> contIf = Handel.newContIf();
    p.init();

    try (NodeDrawer nd = new NodeDrawer(p.new HNodeStatus(), new File(fileName), 10)) {
      do {
        p.network().runMs(10);
        nd.drawNewState(p.network().time, TimeUnit.MILLISECONDS, p.network().liveNodes());
      } while (contIf.test(p));
    }
    System.out.println(fileName + " written - ffmpeg -f gif -i " + fileName + " handel.mp4");
  }

  private void tor() {
    int n = 8;
    System.out.println(
        "\nImpact of the ratio of nodes behind tor - "
            + defaultParams(n, null, null, null, null, null, null, null, null));

    for (double tor : RegistryNodeBuilders.tor()) {
      Handel.HandelParameters params =
          defaultParams(n, null, tor, null, null, null, null, null, null);
      BasicStats bs = run(5, params);
      System.out.println(tor + " tor: " + bs);
    }
  }

  private void noSyncStart() {
    System.out.println("\nImpact of nodes not starting at the same time - " + defaultParams());

    for (int s : new int[] {0, 50, 100, 200, 400, 800}) {
      Handel.HandelParameters params =
          defaultParams(2048, null, 0.0, s, null, null, null, null, null);
      BasicStats bs = run(10, params);
      System.out.println(s + " delay: " + bs);
    }
  }

  private void byzantineSuicide() {
    int n = 512;

    System.out.println(
        "\nByzantine nodes are filling honest node's queues with bad signatures - "
            + defaultParams(n, null, null, null, null, true, null, null, null));

    for (int ni = 128; ni <= 2048; ni *= 2) {
      Handel.HandelParameters params =
          defaultParams(ni, null, null, null, null, true, null, null, null);
      BasicStats bs = run(5, params);
      System.out.println(ni + " nodes, " + params.nodesDown + " byzantines: " + bs);
    }

    for (double dr : new Double[] {0.0, .10, .20, .30, .40, .50}) {
      Handel.HandelParameters params;

      if (dr > 0) {
        params = defaultParams(n, dr, null, null, null, false, null, null, null);
        BasicStats bs = run(2, params);
        System.out.println(n + " nodes, " + dr + " fail-silent: " + bs);
      }

      params = defaultParams(n, dr, null, null, null, true, null, null, null);
      BasicStats bs = run(2, params);
      System.out.println(n + " nodes, " + dr + " byzantines: " + bs);
    }
  }

  private static List<BitSet> allCombine(int total, int set) {
    List<BitSet> res = new ArrayList<>();
    allCombine(res, new BitSet(), total, 0, set);

    return res;
  }

  private static void allCombine(List<BitSet> res, BitSet cur, int total, int pos, int remaining) {
    if (remaining == 0) {
      res.add(cur);
      return;
    }
    if (remaining > total - pos) {
      return;
    }

    BitSet n1 = (BitSet) cur.clone();
    n1.set(pos);
    allCombine(res, n1, total, pos + 1, remaining - 1);
    allCombine(res, cur, total, pos + 1, remaining);
  }

  private void allBadNodePos() {
    int n = 16;

    List<BitSet> ac = allCombine(16, 7);
    System.out.println("\nallBadNodePos: " + ac.size());

    for (BitSet bads : ac) {
      Handel.HandelParameters params =
          new Handel.HandelParameters(
              n,
              9,
              4,
              10,
              20,
              10,
              7,
              RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0),
              NetworkLatency.NetworkNoLatency.class.getSimpleName(),
              0,
              false,
              false,
              null,
              bads);

      params.window = new Handel.WindowParameters(1, 16, 128, new Handel.CongestionExp(2, 4), true);

      BasicStats bs = run(500, params);
      System.out.println(n + " nodes, " + bads + " byzantines: " + bs);
    }
  }

  private void hiddenByzantine() {
    int n = 4096;

    System.out.println(
        "\nByzantine nodes are creating nearly useless signatures"
            + defaultParams(n, null, null, null, null, true, null, null, null));

    for (int ni = 128; ni < n; ni *= 2) {
      Handel.HandelParameters params =
          defaultParams(ni, null, null, null, null, false, true, null, null);
      BasicStats bs = run(2, params);
      System.out.println(ni + " nodes, " + params.nodesDown + " byzantines: " + bs);
    }

    for (double dr : new Double[] {0.0, .10, .20, .30, .40, .50}) {
      Handel.HandelParameters params;

      if (dr > 0) {
        params = defaultParams(n, dr, null, null, null, false, false, null, null);
        BasicStats bs = run(5, params);
        System.out.println(n + " nodes, " + dr + " fail-silent: " + bs);
      }

      params = defaultParams(n, dr, null, null, null, false, true, null, null);
      BasicStats bs = run(5, params);
      System.out.println(n + " nodes, " + dr + " byzantines: " + bs);
    }
  }

  private void byzantineWindowEvaluation() {
    int n = 2048;
    System.out.println("\nSEvaluation with FIXED window size, n;" + n);

    double[] deadRatios = new double[] {0.50};
    // Boolean[][] byzs = new Boolean[][] {new Boolean[] {false, false},
    //    new Boolean[] {true, false}, new Boolean[] {false, true}};
    Boolean[][] byzs = new Boolean[][] {new Boolean[] {true, false}};
    Boolean[] scorings = new Boolean[] {true, false};
    for (Boolean[] byz : byzs) {
      for (Boolean score : scorings) {
        for (int w : new int[] {40, 80, 160}) {
          for (double dr : deadRatios) {
            Handel.HandelParameters params =
                defaultParams(n, dr, null, null, null, byz[0], byz[1], null, null);
            Handel.WindowParameters windowParam =
                new Handel.WindowParameters(w, false, score); // no moving window
            params.window = windowParam;
            BasicStats bs = run(3, params);
            System.out.println(
                "WindowEvaluation: Window: "
                    + w
                    + ", DeadRatio: "
                    + dr
                    + " suicideBiz="
                    + byz[0]
                    + ", hiddenByz="
                    + byz[1]
                    + " => "
                    + bs);
          }
        }
      }

      System.out.println("\nSEvaluation with using ranking in the list *only*");
      for (double dr : deadRatios) {
        Handel.HandelParameters params =
            defaultParams(n, dr, null, null, null, byz[0], byz[1], null, null);
        BasicStats bs = run(3, params);
        System.out.println(
            "ByzantineSuicide: DeadRatio: "
                + dr
                + " suicideBiz="
                + byz[0]
                + ", hiddenByz="
                + byz[1]
                + " => "
                + bs);
      }
    }
  }

  private void byzantineWithVariableWindow2() {
    int n = 4096;
    System.out.println("\nSEvaluation with priority list of variable size; with n=" + n);

    double[] deadRatios = new double[] {0.01, 0.10, 0.20, 0.5};
    int[] minimum = new int[] {4096};
    int[] maximum = new int[] {4096};
    int[] initials = new int[] {4096};
    boolean[] movings = new boolean[] {false};
    Handel.CongestionWindow[] congestions =
        new Handel.CongestionWindow[] {new Handel.CongestionExp(2, 4)};

    Boolean[][] byzs =
        new Boolean[][] {
          new Boolean[] {false, false}, new Boolean[] {false, true}, new Boolean[] {true, false}
        };
    for (Boolean[] byz : byzs) {
      for (double dr : deadRatios) {
        for (boolean moving : movings) {
          for (int init : initials) {
            for (int min : minimum) {
              for (int max : maximum) {
                for (Handel.CongestionWindow c : congestions) {
                  Handel.HandelParameters params =
                      defaultParams(n, dr, 0.20, null, null, byz[0], byz[1], null, null);
                  Handel.WindowParameters windowParam =
                      new Handel.WindowParameters(init, min, max, c, moving);
                  params.window = windowParam;
                  BasicStats bs = run(5, params);

                  String attack = ", att=no";
                  attack = params.byzantineSuicide ? ", att=suicide" : attack;
                  attack = params.hiddenByzantine ? ", att=hidden" : attack;

                  System.out.println(
                      "WindowEvaluation: initial="
                          + init
                          + ",min="
                          + min
                          + ",max="
                          + max
                          + ",cong="
                          + c
                          + ",movingWindow="
                          + moving
                          + ", deadRatio="
                          + dr
                          + attack
                          + "   "
                          + bs);
                }
              }
            }
          }
        }
      }
    }
  }

  private void byzantineWithVariableWindow() {
    int n = 2048;
    System.out.println("\nSEvaluation with priority list of variable size; with n=" + n);

    double[] deadRatios = new double[] {0, 0.50};
    int[] minimum = new int[] {1};
    int[] maximum = new int[] {64};
    int[] initials = new int[] {10};
    // boolean[] movings = new boolean[] {false, true};
    boolean[] movings = new boolean[] {false};

    Handel.CongestionWindow[] congestions =
        new Handel.CongestionWindow[] {
          new Handel.CongestionLinear(1),
          // new Handel.CongestionLinear(10),
          new Handel.CongestionExp(1.5, 2), // new Handel.CongestionExp(2, 2),};
        };
    // Boolean[][] byzs = new Boolean[][] {new Boolean[] {false, false}, new Boolean[] {true,
    // false},
    //   new Boolean[] {false, true}};
    Boolean[][] byzs = new Boolean[][] {new Boolean[] {true, false}};
    for (int init : initials) {
      for (int min : minimum) {
        for (int max : maximum) {
          for (Handel.CongestionWindow c : congestions) {
            for (double dr : deadRatios) {
              for (boolean moving : movings) {
                for (Boolean[] byz : byzs) {
                  Handel.HandelParameters params =
                      defaultParams(n, dr, null, null, null, byz[0], byz[1], null, null);
                  Handel.WindowParameters windowParam =
                      new Handel.WindowParameters(init, min, max, c, moving);
                  params.window = windowParam;
                  BasicStats bs = run(2, params);

                  System.out.println(
                      "initial="
                          + init
                          + ",min="
                          + min
                          + ",max="
                          + max
                          + ",cong="
                          + c
                          + ",movingWindow="
                          + moving
                          + ", deadRatio="
                          + dr
                          + ",suicide="
                          + byz[0]
                          + ",hidden="
                          + byz[1]
                          + "\t=>\t "
                          + bs);
                }
              }
            }
          }
        }
      }
    }
  }

  private void fullComparison() {
    int n = 4;
    int nbRounds = 2;
    double[] deadRatios = new double[] {0, 0.50};
    double[] tors = new double[] {0.20};
    Boolean[][] byzs =
        new Boolean[][] {
          new Boolean[] {false, false}, new Boolean[] {true, false}, new Boolean[] {false, true}
        };
    String[] chooseLevels =
        new String[] {
          Handel.HandelParameters.BESTLEVEL_RANDOM, Handel.HandelParameters.BESTLEVEL_SCORE
        };

    // fixed-length window
    int[] windows = new int[] {64, 1024};
    boolean[] useScore = new boolean[] {true};

    // variable-length window
    int[] minimums = new int[] {1};
    int[] maximums = new int[] {80, 1024};
    int[] initials = new int[] {10};
    boolean[] movings = new boolean[] {false};
    Handel.CongestionWindow[] congestions =
        new Handel.CongestionWindow[] {
          new Handel.CongestionLinear(5),
          // new Handel.CongestionLinear(10),
          new Handel.CongestionExp(1.5, 2), // new Handel.CongestionExp(2, 2),};
        };

    CSVFormatter formatter =
        new CSVFormatter(
            Arrays.asList(
                "n",
                "technique",
                "tor",
                "deadRatio",
                "byLevels",
                "suicide",
                "hidden",
                "window",
                "useScore", // constant length window
                "minimum",
                "maximum",
                "initial",
                "moving",
                "linear",
                "exp_inc",
                "exp_dec",
                // variable-length window
                "doneMin",
                "doneAvg",
                "doneMax")); // general stats

    Handel.HandelParameters params = null;
    BasicStats bs;
    System.out.println(" Naive Runs");
    for (double tor : tors) {
      for (double dr : deadRatios) {
        for (Boolean[] byz : byzs) {

          for (String byLevels : chooseLevels) {
            // 1. naive run
            params = defaultParams(n, null, tor, null, 0, byz[0], byz[1], byLevels, null);
            bs = run(nbRounds, params);
            formatter.add(
                Map.ofEntries(
                    entry("n", n),
                    entry("tor", tor),
                    entry("deadRatio", dr),
                    entry("byLevels", byLevels),
                    entry("suicide", byz[0]),
                    entry("hidden", byz[1]),
                    entry("technique", "ranking"),
                    entry("doneMin", bs.doneAtMin),
                    entry("doneAvg", bs.doneAtAvg),
                    entry("doneMax", bs.doneAtMax)));
          }
        }
      }
    }

    System.out.println(" Fixed Windows ");

    // 2. fixed window
    for (double tor : tors) {
      for (double dr : deadRatios) {
        for (Boolean[] byz : byzs) {

          for (String byLevels : chooseLevels) {
            for (int window : windows) {
              for (boolean score : useScore) {

                params = defaultParams(n, null, tor, null, 0, byz[0], byz[1], byLevels, null);
                Handel.WindowParameters windowParam =
                    new Handel.WindowParameters(window, false, score); // no moving window
                params.window = windowParam;
                bs = run(nbRounds, params);
                formatter.add(
                    Map.ofEntries(
                        entry("n", n),
                        entry("tor", tor),
                        entry("deadRatio", dr),
                        entry("byLevels", byLevels),
                        entry("suicide", byz[0]),
                        entry("hidden", byz[1]),
                        entry("technique", "fixed"),
                        entry("window", window),
                        entry("useScore", score),
                        entry("doneMin", bs.doneAtMin),
                        entry("doneAvg", bs.doneAtAvg),
                        entry("doneMax", bs.doneAtMax)));
              }
            }
          }
        }
      }
    }

    System.out.println("Variable WIndows");
    // 3. variable window
    for (Handel.CongestionWindow c : congestions) {
      for (double tor : tors) {
        for (double dr : deadRatios) {
          for (Boolean[] byz : byzs) {
            for (String byLevels : chooseLevels) {
              for (int minimum : minimums) {
                for (int maximum : maximums) {
                  for (int initial : initials) {
                    for (boolean moving : movings) {

                      params = defaultParams(n, null, tor, null, 0, byz[0], byz[1], byLevels, null);
                      Handel.WindowParameters windowParam =
                          new Handel.WindowParameters(initial, minimum, maximum, c, moving);
                      params.window = windowParam;
                      bs = run(nbRounds, params);
                      Map<String, Object> row = new HashMap<>();
                      Map<String, Object> staticEntries =
                          Map.ofEntries(
                              entry("n", n),
                              entry("tor", tor),
                              entry("deadRatio", dr),
                              entry("byLevels", byLevels),
                              entry("suicide", byz[0]),
                              entry("hidden", byz[1]),
                              entry("technique", c.name()),
                              entry("minimum", minimum),
                              entry("maximum", maximum),
                              entry("initial", initial),
                              entry("moving", moving),
                              entry("doneMin", bs.doneAtMin),
                              entry("doneAvg", bs.doneAtAvg),
                              entry("doneMax", bs.doneAtMax));

                      row.putAll(staticEntries);
                      if (c instanceof Handel.CongestionLinear) {
                        row.put("linear", ((Handel.CongestionLinear) c).delta);
                      } else {
                        Handel.CongestionExp exp = (Handel.CongestionExp) c;
                        row.putAll(
                            Map.ofEntries(
                                entry("exp_inc", exp.increaseFactor),
                                entry("exp_dec", exp.decreaseFactor)));
                      }
                      formatter.add(row);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    System.out.println(formatter.toString());
  }

  void shuffleTimeTest() {
    int n = 1024;
    int nbRounds = 5;
    Handel.HandelParameters params =
        defaultParams(n, null, 0.0, null, null, null, null, null, null);
    String[] shuffle =
        new String[] {Handel.HandelParameters.SHUFFLE_SQUARE, Handel.HandelParameters.SHUFFLE_XOR};
    // new String[] {Handel.HandelParameters.SHUFFLE_XOR};
    // new String[] {Handel.HandelParameters.SHUFFLE_SQUARE};
    // if we remove this, the shuffle square is faster
    // params.window = new Handel.WindowParameters(10, true, true);
    for (String s : shuffle) {
      params.shuffle = s;
      long startTime = System.nanoTime();
      BasicStats bs = run(nbRounds, params);
      long endTime = System.nanoTime();
      System.out.println(s + " delay: " + bs);
      System.out.println("With shuffling " + s + " -> " + (endTime - startTime) / 1000000);
    }
  }

  void genAnim() {
    int n = 4096;
    // runOnce(defaultParams(n, null, null, 200, null, null), "unsync.gif");
    runOnce(
        defaultParams(
            n, null, 0.0, null, null, null, null, null, RegistryNodeBuilders.Location.CITIES),
        "tor.gif");
  }

  void delayedStartImpact(int n, int waitTime, int period) {
    int mF = 0;
    int mS = 0;

    for (int time = 0; time <= 1000; time += period) {

      for (int l = 1; l <= MoreMath.log2(n); l++) {
        mF++;
        if (time >= (l - 1) * waitTime) {
          mS++;
        }
      }
    }
    System.out.println(
        "Sent w/o waitTime: "
            + mF
            + ", w/ waitTime:"
            + mS
            + ", saved= "
            + (mF - mS)
            + " - "
            + ((mF - mS) / (0.0 + mS)));
  }

  private void log() throws IOException {
    System.out.println("\nBehavior when the number of nodes increases - " + defaultParams());
    System.out.println(" We expect log performances and polylog number of messages.");

    Graph.Series tA = new Graph.Series("average time");
    Graph.Series tM = new Graph.Series("maximum time");
    Graph.Series mA = new Graph.Series("average number of messages");
    Graph.Series mM = new Graph.Series("maximum number of messages");

    for (int n = 128; n <= 4096; n *= 2) {
      Handel.HandelParameters params =
          defaultParams(n, null, null, null, null, null, null, null, null);
      BasicStats bs = run(5, params);
      System.out.println(n + " nodes: " + bs);

      tA.addLine(new Graph.ReportLine(n, bs.doneAtAvg));
      tM.addLine(new Graph.ReportLine(n, bs.doneAtMax));
      mA.addLine(new Graph.ReportLine(n, bs.msgRcvAvg));
      mM.addLine(new Graph.ReportLine(n, bs.msgRcvMax));
    }

    Graph graph =
        new Graph(
            "time vs. number of nodes" + defaultParams().toString(),
            "number of nodes",
            "time in milliseconds");

    graph.addSerie(tA);
    graph.addSerie(tM);
    graph.save(new File("handel_log_time.png"));

    graph =
        new Graph(
            "messages vs. number of nodes" + defaultParams().toString(),
            "number of nodes",
            "number of messages");
    graph.addSerie(mA);
    graph.addSerie(mM);
    graph.save(new File("handel_log_msg.png"));
  }

  private void logPeriodTime(RegistryNodeBuilders.Location loc, double dead, double tor)
      throws IOException {
    boolean print = false;

    Graph.Series tA = new Graph.Series("average time");
    Graph.Series mA = new Graph.Series("average number of messages");

    int n = 4096;
    int r = 5;
    for (int p : new int[] {1, 5, 10, 15, 20, 40, 80}) {
      Handel.HandelParameters params = defaultParams(n, dead, tor, p, null, null, null, null, loc);
      if (!print) {
        print = true;
        System.out.println("Changing period time");
        System.out.println("params: " + params);
      }

      BasicStats bs = run(r, params);
      System.out.println(n + " ;" + p + ";" + bs.msgRcvAvg + "; " + bs.doneAtAvg);

      tA.addLine(new Graph.ReportLine(p, bs.doneAtAvg));
      mA.addLine(new Graph.ReportLine(p, bs.msgRcvAvg));
    }

    Graph graph =
        new Graph(
            "time vs. period time" + defaultParams().toString(),
            "period time in ms",
            "time in milliseconds");

    graph.addSerie(tA);
    graph.save(new File("handel_period_time.png"));

    graph =
        new Graph(
            "messages vs. period time" + defaultParams().toString(),
            "period time in ms",
            "number of messages");
    graph.addSerie(mA);
    graph.save(new File("handel_period_msg.png"));
  }

  private void logDelayedStart(RegistryNodeBuilders.Location loc, double dead, double tor)
      throws IOException {
    boolean print = false;

    Graph.Series tA = new Graph.Series("average time");
    Graph.Series mA = new Graph.Series("average number of messages");

    int n = 4096;
    int r = 10;
    for (int s : new int[] {0, 10, 20, 30, 50, 70, 100}) {
      Handel.HandelParameters params =
          defaultParams(n, dead, tor, null, null, null, null, null, loc);
      params.desynchronizedStart = s;

      if (!print) {
        print = true;
        System.out.println("Nodes starts at different time");
        System.out.println("params: " + params);
      }

      BasicStats bs = run(r, params);
      System.out.println(n + " ;" + s + ";" + bs.msgRcvAvg + "; " + bs.doneAtAvg);

      tA.addLine(new Graph.ReportLine(s, bs.doneAtAvg));
      mA.addLine(new Graph.ReportLine(s, bs.msgRcvAvg));
    }

    Graph graph =
        new Graph(
            "time vs. delayed start" + defaultParams().toString(),
            "delay in ms",
            "time in milliseconds");

    graph.addSerie(tA);
    graph.setForcedMinY(0.0);
    graph.save(new File("handel_delayedStart_time.png"));

    graph =
        new Graph(
            "messages vs. start time" + defaultParams().toString(),
            "start time in ms",
            "number of messages");
    graph.addSerie(mA);
    graph.setForcedMinY(0.0);
    graph.save(new File("handel_delayedStart_msg.png"));
  }

  private void logStartTime(RegistryNodeBuilders.Location loc, double dead, double tor)
      throws IOException {
    boolean print = false;

    Graph.Series tA = new Graph.Series("average time");
    Graph.Series mA = new Graph.Series("average number of messages");

    int n = 4096;
    int r = 5;
    for (int s : new int[] {0, 25, 50, 75, 100}) {
      Handel.HandelParameters params =
          defaultParams(n, dead, tor, null, null, null, null, null, loc);
      params.levelWaitTime = s;

      if (!print) {
        print = true;
        System.out.println("Changing level start time");
        System.out.println("params: " + params);
      }

      BasicStats bs = run(r, params);
      System.out.println(n + " ;" + s + ";" + bs.msgRcvAvg + "; " + bs.doneAtAvg);

      tA.addLine(new Graph.ReportLine(s, bs.doneAtAvg));
      mA.addLine(new Graph.ReportLine(s, bs.msgRcvAvg));
    }

    Graph graph =
        new Graph(
            "time vs. start time" + defaultParams().toString(),
            "start time in ms",
            "time in milliseconds");

    graph.addSerie(tA);
    graph.save(new File("handel_startTime_time.png"));

    graph =
        new Graph(
            "messages vs. start time" + defaultParams().toString(),
            "start time in ms",
            "number of messages");
    graph.addSerie(mA);
    graph.save(new File("handel_startTime_msg.png"));
  }

  private void logContactedNode(RegistryNodeBuilders.Location loc, double dead, double tor)
      throws IOException {
    boolean print = false;

    Graph.Series tA = new Graph.Series("average time");
    Graph.Series mA = new Graph.Series("average number of messages");

    int n = 4096;
    int r = 5;
    for (int a : new int[] {0, 5, 10, 20, 40}) {
      Handel.HandelParameters params =
          defaultParams(n, dead, tor, null, null, null, null, null, loc);
      params.acceleratedCallsCount = a;

      if (!print) {
        print = true;
        System.out.println("Changing contacted nodes");
        System.out.println("params: " + params);
      }

      BasicStats bs = run(r, params);
      System.out.println(n + " ;" + a + ";" + bs.msgRcvAvg + "; " + bs.doneAtAvg);

      tA.addLine(new Graph.ReportLine(a, bs.doneAtAvg));
      mA.addLine(new Graph.ReportLine(a, bs.msgRcvAvg));
    }

    Graph graph =
        new Graph(
            "time vs. fast path peer count" + defaultParams().toString(),
            "fast path peer count",
            "time in milliseconds");

    graph.addSerie(tA);
    graph.save(new File("handel_fastpath_time.png"));

    graph =
        new Graph(
            "messages vs. start time" + defaultParams().toString(),
            "fast path peer count",
            "number of messages");
    graph.addSerie(mA);
    graph.save(new File("handel_fastpath_msg.png"));
  }

  public static void allScenarios() throws IOException {
    HandelScenarios scenario = new HandelScenarios();
    scenario.logStartTime(RegistryNodeBuilders.Location.AWS, 0.01, 0.01);
    scenario.logContactedNode(RegistryNodeBuilders.Location.AWS, 0.01, 0.01);
    scenario.logPeriodTime(RegistryNodeBuilders.Location.AWS, 0.01, 0.01);

    scenario.logStartTime(RegistryNodeBuilders.Location.CITIES, 0.01, 0.01);
    scenario.logContactedNode(RegistryNodeBuilders.Location.CITIES, 0.01, 0.01);
    scenario.logPeriodTime(RegistryNodeBuilders.Location.CITIES, 0.01, 0.01);

    scenario.logStartTime(RegistryNodeBuilders.Location.CITIES, 0.2, 0.01);
    scenario.logContactedNode(RegistryNodeBuilders.Location.CITIES, 0.2, 0.01);
    scenario.logPeriodTime(RegistryNodeBuilders.Location.CITIES, 0.2, 0.01);

    scenario.logStartTime(RegistryNodeBuilders.Location.AWS, 0.2, 0.01);
    scenario.logContactedNode(RegistryNodeBuilders.Location.AWS, 0.2, 0.01);
    scenario.logPeriodTime(RegistryNodeBuilders.Location.AWS, 0.2, 0.01);

    scenario.logStartTime(RegistryNodeBuilders.Location.CITIES, 0.2, 0.2);
    scenario.logContactedNode(RegistryNodeBuilders.Location.CITIES, 0.2, 0.2);
    scenario.logPeriodTime(RegistryNodeBuilders.Location.CITIES, 0.2, 0.2);
  }

  public static void main(String... args) throws IOException {
    HandelScenarios scenario = new HandelScenarios();
    scenario.logDelayedStart(RegistryNodeBuilders.Location.CITIES, 0.2, 0.00);
  }
}
