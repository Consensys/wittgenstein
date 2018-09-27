package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("WeakerAccess")
public abstract class NetworkLatency {
    /**
     * @param delta - a number between 0 & 99. Used to randomize the result
     */
    public abstract int getDelay(@NotNull Node from, @NotNull Node to, int delta);

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }


    /**
     * Latency with: max distance = (10ms + 200ms) +/- 50%, linear
     */
    public static class NetworkLatencyByDistance extends NetworkLatency {
        public final int fix;
        public final int max;
        public final int spread = 50;

        @Override
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            if (delta < 0 || delta > 99) {
                throw new IllegalArgumentException("delta=" + delta);
            }
            int rawDelay = fix + (max * from.dist(to)) / Node.MAX_DIST;
            return (int) (rawDelay * (100 + spread - delta) / 100.0);
        }

        public NetworkLatencyByDistance() {
            this(10, 200);
        }

        public NetworkLatencyByDistance(int fix, int max) {
            this.fix = fix;
            this.max = max;
        }

        @Override
        public String toString() {
            return "NetworkLatencyByDistance{" +
                    "fix=" + fix +
                    ", max=" + max +
                    ", spread=" + spread +
                    "%}";
        }
    }

    public static class NetworkNoLatency extends NetworkLatency {
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            return 1;
        }
    }

    public static class MeasuredNetworkLatency extends NetworkLatency {
        private final int[] longDistrib = new int[100];

        /**
         * @see Network#distribProp
         * @see Network#distribVal
         */
        private void setLatency(@NotNull int[] dP, @NotNull int[] dV) {
            int li = 0;
            int cur = 0;
            int sum = 0;
            for (int i = 0; i < dP.length; i++) {
                sum += dP[i];
                int step = (dV[i] - cur) / dP[i];
                for (int ii = 0; ii < dP[i]; ii++) {
                    cur += step;
                    longDistrib[li++] = cur;
                }
            }

            if (sum != 100) throw new IllegalArgumentException();
            if (li != 100) throw new IllegalArgumentException();
        }


        public MeasuredNetworkLatency(@NotNull int[] distribProp, @NotNull int[] distribVal) {
            setLatency(distribProp, distribVal);
        }

        @Override
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            if (delta < 0 || delta > 99) {
                throw new IllegalArgumentException("delta=" + delta);
            }
            return longDistrib[delta];
        }

        /**
         * Print the latency distribution:
         * - the first 50ms, 10ms by 10ms
         * - then, until 500ms: each 100ms
         * - then each second
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BlockChainNetwork latency: time to receive a message:\n");

            int cur = 0;
            for (int ms = 10; cur < 100 & ms < 600; ms += (ms < 50 ? 10 : ms == 50 ? 50 : 100)) {
                int size = 0;
                while (cur < longDistrib.length && longDistrib[cur] < ms) {
                    size++;
                    cur++;
                }
                sb.append(ms).append("ms ").append(size).append("%, cumulative ");
                sb.append(cur).append("%\n");
            }

            for (int s = 1; cur < 100; s++) {
                int size = 0;
                while (cur < longDistrib.length && longDistrib[cur] < s * 1000) {
                    size++;
                    cur++;
                }
                sb.append(s).append(" second").append(s > 1 ? "s: " : ": ").append(size).append("%, cumulative ");

                sb.append(cur).append("%\n");
            }

            return sb.toString();
        }
    }
}
