package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * A simple (and even simplistic) implementation of the Paxos protocol, using the algorithm described here:
 * https://www.the-paper-trail.org/post/2009-02-03-consensus-protocols-paxos/
 * <p>
 * An important point is the latency, as we have just a few nodes. As a result a proposer close to
 * the acceptors will get most of its proposals accepted, while a proposers further than the other
 * will get its proposals rejected. As there are just a few nodes this effect not limited by a kind
 * a law of the large numbers.
 */
public class Paxos implements Protocol {
  private final Network<PaxosNode> network = new Network<>();
  private final List<AcceptorNode> acceptors = new ArrayList<>();
  final List<ProposerNode> proposers = new ArrayList<>();
  private final int delayBetweenProposals;
  private final int timeout;
  final int majority;
  private final int proposerCount;
  private final int acceptorCount;
  private final NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

  Paxos(int delayBetweenProposals, int timeout, int proposerCount, int accepterCount) {
    this.delayBetweenProposals = delayBetweenProposals;
    this.timeout = timeout;
    this.proposerCount = proposerCount;
    this.acceptorCount = accepterCount;
    this.majority = accepterCount / 2 + 1;
  }

  static class Propose extends Message<PaxosNode> {
    final int seq;

    Propose(int seq) {
      this.seq = seq;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((AcceptorNode) to).onPropose(from, this);
    }
  }


  static class Agree extends Message<PaxosNode> {
    final int yourSeq;

    Agree(int yourSeq) {
      this.yourSeq = yourSeq;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((ProposerNode) to).onAgree(yourSeq);
    }
  }


  static class Commit extends Message<PaxosNode> {
    final int seq;

    Commit(int seq) {
      this.seq = seq;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((AcceptorNode) to).onCommit(from, seq);
    }
  }


  static class Accept extends Message<PaxosNode> {
    final int yourSeq;

    Accept(int yourSeq) {
      this.yourSeq = yourSeq;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((ProposerNode) to).onAccept(yourSeq);
    }
  }


  static class RejectOnCommit extends Message<PaxosNode> {
    final int seqRejected;
    final int seqAccepted;

    RejectOnCommit(int seqRejected, int seqAccepted) {
      this.seqRejected = seqRejected;
      this.seqAccepted = seqAccepted;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((ProposerNode) to).onRejectOnCommit(seqRejected, seqAccepted);
    }
  }


  static class Reject extends Message<PaxosNode> {
    final int seqRejected;
    final int seqAccepted;

    Reject(int seqRejected, int seqAccepted) {
      this.seqRejected = seqRejected;
      this.seqAccepted = seqAccepted;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((ProposerNode) to).onReject(seqRejected, seqAccepted);
    }
  }


  static class PaxosNode extends Node {
    PaxosNode(Random rd, NodeBuilder nb) {
      super(rd, nb);
    }
  }


  class AcceptorNode extends PaxosNode {
    int maxAgreed;
    ProposerNode agreedTo;

    AcceptorNode(Random rd, NodeBuilder nb) {
      super(rd, nb);
    }

    void onPropose(PaxosNode from, Propose p) {
      if (p.seq < maxAgreed) {
        Reject r = new Reject(p.seq, maxAgreed);
        network.send(r, this, from);
      } else if (p.seq == maxAgreed) {
        // Should not happen, we we don't have any message duplication in the network
        //  and this protocol does not have byzantine nodes
        throw new IllegalStateException(this + " " + p);
      } else {
        Agree a = new Agree(p.seq);
        maxAgreed = p.seq;
        agreedTo = (ProposerNode) from;
        network.send(a, this, from);
      }
    }

    void onCommit(PaxosNode from, int seq) {
      if (seq != maxAgreed) {
        // Can happen:
        //  1) if I agreed to a new value in between
        //  2) if the majority (but not me) agreed at the previous step but we didn't.
        RejectOnCommit r = new RejectOnCommit(seq, maxAgreed);
        network.send(r, this, from);
      } else {
        Accept a = new Accept(seq);
        network.send(a, this, from);
      }
    }

    @Override
    public String toString() {
      return "AcceptorNode{" + "maxAgreed=" + maxAgreed + '}';
    }
  }


  class ProposerNode extends PaxosNode {
    int rank;
    int seqIP;
    int agreeCountIP;
    int reject1CountIP;
    int acceptCountIP;
    int reject2CountIP;
    boolean proposalIP = false;
    int seqAccepted;

    // Statistics on the proposal made
    int agreeCount = 0;
    int reject1Count = 0;
    int acceptCount = 0;
    int reject2Count = 0;
    int timeoutCount = 0;

    ProposerNode(int rank, Random rd, NodeBuilder nb) {
      super(rd, nb);
      this.rank = rank;
    }

    void onReject(int seq, int serverCurSeq) {
      if (seq == seqIP) {
        reject1CountIP++;
        if (reject1CountIP >= majority - 1) {
          proposalIP = false;
          seqAccepted = Math.max(seqAccepted, serverCurSeq);
          reject1Count++;
          startNextProposal();
        }
      }
    }

    void onAgree(int seq) {
      if (seq == seqIP) {
        agreeCountIP++;
        if (agreeCountIP >= majority) {
          agreeCount++;
          Commit c = new Commit(seqIP);
          sendToAcceptors(c, network.time + 1);
        }
      }
    }

    void onAccept(int seq) {
      if (seq == seqIP) {
        acceptCountIP++;
        if (acceptCountIP >= majority) {
          proposalIP = false;
          seqAccepted = Math.max(seqAccepted, seq);
          acceptCount++;
          startNextProposal();
        }
      }
    }

    void onRejectOnCommit(int seq, int serverCurSeq) {
      if (seq == seqIP) {
        reject2CountIP++;
        if (reject2CountIP >= majority - 1) {
          proposalIP = false;
          seqAccepted = Math.max(seqAccepted, serverCurSeq);
          reject2Count++;
          startNextProposal();
        }
      }
    }

    private void sendToAcceptors(Message<PaxosNode> m, int sentTime) {
      List<PaxosNode> dest = new ArrayList<>(acceptors);
      Collections.shuffle(dest, network.rd);
      network.send(m, sentTime, this, dest);
    }

    void onTimeout(int seq) {
      if (seq == seqIP && proposalIP) {
        proposalIP = false;
        timeoutCount++;
        startNextProposal();
      }
    }

    void startNextProposal() {
      if (proposalIP) {
        throw new IllegalStateException();
      }
      proposalIP = true;
      reject1CountIP = 0;
      agreeCountIP = 0;
      acceptCountIP = 0;

      // This to ensure:
      // 1) Two different proposers will always use a different seq number
      // 2) The same proposer will always use a different (and incremental) seq number
      int gap = seqAccepted % proposerCount;
      int newSeqIP = seqAccepted + proposerCount - gap + rank;
      seqIP = newSeqIP > seqIP ? newSeqIP : seqIP + proposerCount;

      Propose p = new Propose(seqIP);
      int sentTime = delayBetweenProposals == 0 ? network.time + 1
          : network.time + network.rd.nextInt(delayBetweenProposals) + 1;
      sendToAcceptors(p, sentTime);
      network.registerTask(() -> onTimeout(p.seq), sentTime + timeout, this);
    }

    @Override
    public String toString() {
      return "ProposerNode{" + "rank=" + rank + ", seqIP=" + seqIP + ", agreeCountIP="
          + agreeCountIP + ", reject1CountIP=" + reject1CountIP + ", acceptCountIP=" + acceptCountIP
          + ", reject2CountIP=" + reject2CountIP + ", proposalIP=" + proposalIP + ", seqAccepted="
          + seqAccepted + ", agreeCount=" + agreeCount + ", reject1Count=" + reject1Count
          + ", acceptCount=" + acceptCount + ", reject2Count=" + reject2Count + ", timeoutCount="
          + timeoutCount + '}';
    }
  }

  @Override
  public Network<PaxosNode> network() {
    return network;
  }

  @Override
  public Paxos copy() {
    return new Paxos(delayBetweenProposals, timeout, proposerCount, acceptorCount);
  }

  @Override
  public void init() {
    network.networkLatency = new NetworkLatency.NetworkLatencyByDistance();

    for (int i = 0; i < acceptorCount; i++) {
      AcceptorNode an = new AcceptorNode(network.rd, nb);
      network.addNode(an);
      acceptors.add(an);
    }

    for (int i = 0; i < proposerCount; i++) {
      ProposerNode an = new ProposerNode(i, network.rd, nb);
      network.addNode(an);
      proposers.add(an);
      an.startNextProposal();
    }
  }

  void play() {
    List<StatsHelper.StatsGetter> statsToGet = new ArrayList<>();
    statsToGet.add(new StatsHelper.SimpleStatsGetter() {
      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        List<Node> proposers =
            liveNodes.stream().filter(n -> n instanceof ProposerNode).collect(Collectors.toList());
        return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).acceptCount);
      }

      @Override
      public String toString() {
        return "acceptCount";
      }
    });
    statsToGet.add(new StatsHelper.SimpleStatsGetter() {
      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        List<Node> proposers =
            liveNodes.stream().filter(n -> n instanceof ProposerNode).collect(Collectors.toList());
        return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).timeoutCount);
      }

      @Override
      public String toString() {
        return "timeoutCount";
      }
    });
    statsToGet.add(new StatsHelper.SimpleStatsGetter() {
      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        List<Node> proposers =
            liveNodes.stream().filter(n -> n instanceof ProposerNode).collect(Collectors.toList());
        return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).reject1Count);
      }

      @Override
      public String toString() {
        return "reject1Count";
      }
    });
    statsToGet.add(new StatsHelper.SimpleStatsGetter() {
      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        List<Node> proposers =
            liveNodes.stream().filter(n -> n instanceof ProposerNode).collect(Collectors.toList());
        return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).reject2Count);
      }

      @Override
      public String toString() {
        return "reject2Count";
      }
    });
    statsToGet.add(new StatsHelper.SimpleStatsGetter() {
      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return StatsHelper.getStatsOn(liveNodes, Node::getMsgReceived);
      }

      @Override
      public String toString() {
        return "getMsgReceived";
      }
    });


    RunMultipleTimes rmt = new RunMultipleTimes(this, 10, statsToGet);
    List<StatsHelper.Stat> res = rmt.run(protocol -> protocol.network().time < 100_000);

    StatsHelper.SimpleStats ac = (StatsHelper.SimpleStats) res.get(0);
    StatsHelper.SimpleStats to = (StatsHelper.SimpleStats) res.get(1);
    StatsHelper.SimpleStats r1 = (StatsHelper.SimpleStats) res.get(2);
    StatsHelper.SimpleStats r2 = (StatsHelper.SimpleStats) res.get(3);
    StatsHelper.SimpleStats mr = (StatsHelper.SimpleStats) res.get(4);

    double successRate = 1.0 * ac.avg / (ac.avg + r1.avg + r2.avg + to.avg);
    successRate = (int) (successRate * 10000) / 100.0;

    System.out.println("acceptors: " + acceptorCount + ", accepted=(" + ac + "), avg success rate:"
        + successRate + "%, msg received=(" + mr + ")");
  }

  private static void msgPerValidators() {
    for (int a = 3; a < 50; a += 2) {
      Paxos p = new Paxos(1000, 400, 3, a);
      p.play();
    }
  }

  public static void main(String... args) {
    // new Paxos(0, 400, 3, 77).play();
    msgPerValidators();
  }
}
