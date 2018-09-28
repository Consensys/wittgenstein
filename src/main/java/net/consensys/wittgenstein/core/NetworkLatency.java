package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("WeakerAccess")
public abstract class NetworkLatency {
    /**
     * @param delta - a random number between 0 & 99. Used to randomize the result
     */
    public abstract int getDelay(@NotNull Node from, @NotNull Node to, int delta);

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    protected void checkDelta(int delta) {
        if (delta < 0 || delta > 99) {
            throw new IllegalArgumentException("delta=" + delta);
        }
    }

    /**
     * Latency
     *
     * @see <a href="https://pdfs.semanticscholar.org/ff13/5d221678e6b542391c831e87fca56e830a73.pdf"/>
     * Latency vs. distance: y = 0.022x + 4.862
     * @see <a href="https://www.jstage.jst.go.jp/article/ipsjjip/22/3/22_435/_pdf"/>
     * variance with ARIMA
     * @see <a href="https://core.ac.uk/download/pdf/19439477.pdf"/>
     * generalized Pareto distribution:
     * - μ [ms] = −0,3
     * - σ [ms] = 0.35
     * - ξ[-]   = 1.4
     * <p>
     * TODO: we should take the size into account....
     */
    public static class NetworkLatencyByDistance extends NetworkLatency {
        public final int fix;
        public final int max;

        private double distToMile(int dist) {
            final double earthPerimeter = 24_860;
            double pointValue = earthPerimeter / Node.MAX_DIST;
            return pointValue * dist;
        }

        @Override
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            checkDelta(delta);
            double rawDelay = fix + distToMile(from.dist(to)) * 0.022 + 5;
            return Math.max(fix, (int) (rawDelay));
        }

        public NetworkLatencyByDistance() {
            this(10, 150);
        }

        public NetworkLatencyByDistance(int fixDelay, int maxLatency) {
            this.fix = fixDelay;
            this.max = maxLatency;
        }


        @Override
        public String toString() {
            return "NetworkLatencyByDistance{" +
                    "fix=" + fix +
                    ", max=" + max +
                    "%}";
        }
    }

    /*
    cnt=10, sendPeriod=20 P2PSigNode{nodeId=499, doneAt=550, sigs=405, msgReceived=133, msgSent=145, KBytesSent=148, KBytesReceived=161}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=999, doneAt=637, sigs=515, msgReceived=228, msgSent=69, KBytesSent=210, KBytesReceived=221}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=1499, doneAt=663, sigs=953, msgReceived=153, msgSent=146, KBytesSent=448, KBytesReceived=649}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=1999, doneAt=689, sigs=1295, msgReceived=438, msgSent=142, KBytesSent=1173, KBytesReceived=1281}
cnt=10, sendPeriod=20 P2PSigNode{nodeId=2499, doneAt=793, sigs=1402, msgReceived=289, msgSent=478, KBytesSent=1212, KBytesReceived=908}

     */

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
            checkDelta(delta);
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
