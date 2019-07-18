package net.consensys.wittgenstein.protocols;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.StatsHelper;

/**
 * A simple implementation of the Paxos protocol, using the algorithm described here:
 * https://www.the-paper-trail.org/post/2009-02-03-consensus-protocols-paxos/ or here (in
 * french...): https://www.youtube.com/watch?v=cj9DCYac3dw
 *
 * <p>An important point is the latency, as we have just a few nodes. As a result a proposer close
 * to the acceptors will get most of its proposals accepted, while a proposers further than the
 * other will get its proposals rejected. As there are just a few nodes this effect not limited by a
 * kind a law of the large numbers.
 */
public class Paxos implements Protocol {
  private static final int MAX_VAL = 1000;
  private final Network<PaxosNode> network = new Network<>();
  private final List<AcceptorNode> acceptors = new ArrayList<>();
  final List<ProposerNode> proposers = new ArrayList<>();
  final int majority;
  private final PaxosParameters params;
  private final NodeBuilder nb;

  public Paxos(PaxosParameters params) {
    this.params = params;
    this.majority = params.acceptorCount / 2 + 1;
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilder);
    this.network.setNetworkLatency(RegistryNetworkLatencies.singleton.getByName(params.latency));
  }

  /**
   * Sent by a proposer at the first round. Sequence number are incremental; partitioned by
   * proposer.
   */
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

  /** Sent by an acceptor at the first round if it refuses the Proposal. */
  static class Reject extends Message<PaxosNode> {
    /** The seq sent by the proposer, and refused by the acceptor. */
    final int seqRejected;
    /**
     * The seq already accepted by the acceptor; eg. the proposer will need to send a seq number
     * greater to this one.
     */
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

  /** Sent by an acceptor during the first round, as a reply to a proposal. */
  static class Agree extends Message<PaxosNode> {
    /** The seq number sent by the proposer, and agreed by the acceptor. */
    final int yourSeq;
    /** The value previously accepted by this acceptor. */
    final Integer acceptedSeq;

    final Integer acceptedVal;

    Agree(int yourSeq, Integer acceptedSeq, Integer acceptedVal) {
      this.yourSeq = yourSeq;
      this.acceptedSeq = acceptedSeq;
      this.acceptedVal = acceptedVal;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((ProposerNode) to).onAgree(yourSeq, acceptedSeq, acceptedVal);
    }
  }

  /**
   * Sent by a proposer at the second round, if the first round was successful (i.e. a majority of
   * agree)
   */
  static class Commit extends Message<PaxosNode> {
    final int seq;
    final int val;

    Commit(int seq, int val) {
      this.seq = seq;
      this.val = val;
    }

    @Override
    public void action(Network<PaxosNode> network, PaxosNode from, PaxosNode to) {
      ((AcceptorNode) to).onCommit(from, seq, val);
    }
  }

  /** Sent by the acceptor when it accepts the value at the second round. */
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

  /** Sent by the acceptor when it refuses the value during the second round. */
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

  static class PaxosNode extends Node {
    PaxosNode(Random rd, NodeBuilder nb) {
      super(rd, nb);
    }
  }

  class AcceptorNode extends PaxosNode {
    int maxAgreed = -1;
    Integer acceptedSeq;
    Integer acceptedVal;
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
        Agree a = new Agree(p.seq, acceptedSeq, acceptedVal);
        maxAgreed = p.seq;
        agreedTo = (ProposerNode) from;
        network.send(a, this, from);
      }
    }

    void onCommit(PaxosNode from, int seq, int val) {
      if (seq != maxAgreed || (acceptedVal != null && acceptedVal != val)) {
        // Can happen:
        //  1) if I agreed to a new value in between
        //  2) if the majority (but not me) agreed at the previous step but we didn't.
        RejectOnCommit r = new RejectOnCommit(seq, maxAgreed);
        network.send(r, this, from);
      } else {
        acceptedVal = val;
        acceptedSeq = acceptedSeq == null ? seq : Math.max(acceptedSeq, seq);
        Accept a = new Accept(seq);
        network.send(a, this, from);
      }
    }

    @Override
    public String toString() {
      return "AcceptorNode{"
          + "maxAgreed="
          + maxAgreed
          + ", acceptedSeq="
          + acceptedSeq
          + ", acceptedVal="
          + acceptedVal
          + ", agreedTo="
          + agreedTo
          + '}';
    }
  }

  class ProposerNode extends PaxosNode {
    final int rank;
    final int valueProposed;
    Integer valueAccepted;

    Integer acceptedSeqIP;
    Integer acceptedValIP;

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
    int reject2Count = 0;
    int timeoutCount = 0;

    ProposerNode(int rank, Random rd, NodeBuilder nb) {
      super(rd, nb);
      this.rank = rank;
      this.valueProposed = rd.nextInt(MAX_VAL);
    }

    void onReject(int seq, int serverCurSeq) {
      if (seq == seqIP) {
        reject1CountIP++;
        if (reject1CountIP == majority) {
          proposalIP = false;
          seqAccepted = Math.max(seqAccepted, serverCurSeq);
          reject1Count++;
          startNextProposal();
        }
      }
    }

    void onAgree(int seq, Integer acceptedSeq, Integer acceptedVal) {
      if (seq == seqIP && agreeCountIP < majority) {
        agreeCountIP++;
        if (acceptedSeq != null) {
          if (this.acceptedSeqIP == null || this.acceptedSeqIP < acceptedSeq) {
            this.acceptedSeqIP = acceptedSeq;
            this.acceptedValIP = acceptedVal;
          }
        }
        if (agreeCountIP >= majority) {
          agreeCount++;
          if (this.acceptedValIP == null) {
            this.acceptedValIP = valueProposed;
          }
          Commit c = new Commit(seqIP, this.acceptedValIP);
          sendToAcceptors(c, network.time + 1);
        }
      }
    }

    void onAccept(int seq) {
      if (seq == seqIP && acceptCountIP < majority) {
        acceptCountIP++;
        if (acceptCountIP >= majority) {
          proposalIP = false;
          if (acceptedValIP == null) {
            throw new IllegalStateException();
          }
          if (valueAccepted != null) {
            throw new IllegalStateException("Already accepted a value");
          }
          valueAccepted = acceptedValIP;
          doneAt = network.time;
        }
      }
    }

    void onRejectOnCommit(int seq, int serverCurSeq) {
      if (seq == seqIP) {
        reject2CountIP++;
        if (reject2CountIP == majority) {
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

      acceptedSeqIP = null;
      acceptedValIP = null;

      proposalIP = true;
      agreeCountIP = 0;
      reject1CountIP = 0;
      acceptCountIP = 0;
      reject2CountIP = 0;

      // This to ensure:
      // 1) Two different proposers will always use a different seq number
      // 2) The same proposer will always use a different (and incremental) seq number
      int gap = seqAccepted % params.proposerCount;
      int newSeqIP = seqAccepted + params.proposerCount - gap + rank;
      seqIP = newSeqIP > seqIP ? newSeqIP : seqIP + params.proposerCount;

      Propose p = new Propose(seqIP);
      int sentTime = network.time + 1;
      sendToAcceptors(p, sentTime);
      network.registerTask(() -> onTimeout(p.seq), sentTime + params.timeout, this);
    }
  }

  @Override
  public Network<PaxosNode> network() {
    return network;
  }

  @Override
  public Paxos copy() {
    return new Paxos(params);
  }

  @SuppressWarnings("WeakerAccess")
  public static class PaxosParameters extends WParameters {
    final int acceptorCount;
    final int proposerCount;
    final int timeout;
    final String nodeBuilder;
    final String latency;

    public PaxosParameters() {
      this(3, 3, 1000, null, null);
    }

    public PaxosParameters(
        int acceptorCount, int proposerCount, int timeout, String nodeBuilder, String latency) {
      this.acceptorCount = acceptorCount;
      this.proposerCount = proposerCount;
      this.timeout = timeout;
      this.nodeBuilder = nodeBuilder;
      this.latency = latency;
    }
  }

  @Override
  public void init() {
    for (int i = 0; i < params.acceptorCount; i++) {
      AcceptorNode an = new AcceptorNode(network.rd, nb);
      network.addNode(an);
      acceptors.add(an);
    }

    for (int i = 0; i < params.proposerCount; i++) {
      ProposerNode an = new ProposerNode(i, network.rd, nb);
      network.addNode(an);
      proposers.add(an);
      an.startNextProposal();
    }
  }

  @Override
  public String toString() {
    return "Paxos{" + "params=" + params + '}';
  }

  void play() {
    List<StatsHelper.StatsGetter> statsToGet = new ArrayList<>();
    statsToGet.add(
        new StatsHelper.SimpleStatsGetter() {
          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            List<Node> proposers =
                liveNodes.stream()
                    .filter(n -> n instanceof ProposerNode)
                    .collect(Collectors.toList());
            return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).doneAt);
          }

          @Override
          public String toString() {
            return "doneAt";
          }
        });
    statsToGet.add(
        new StatsHelper.SimpleStatsGetter() {
          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            List<Node> proposers =
                liveNodes.stream()
                    .filter(n -> n instanceof ProposerNode)
                    .collect(Collectors.toList());
            return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).timeoutCount);
          }

          @Override
          public String toString() {
            return "timeoutCount";
          }
        });
    statsToGet.add(
        new StatsHelper.SimpleStatsGetter() {
          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            List<Node> proposers =
                liveNodes.stream()
                    .filter(n -> n instanceof ProposerNode)
                    .collect(Collectors.toList());
            return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).reject1Count);
          }

          @Override
          public String toString() {
            return "reject1Count";
          }
        });
    statsToGet.add(
        new StatsHelper.SimpleStatsGetter() {
          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            List<Node> proposers =
                liveNodes.stream()
                    .filter(n -> n instanceof ProposerNode)
                    .collect(Collectors.toList());
            return StatsHelper.getStatsOn(proposers, p -> ((ProposerNode) p).reject2Count);
          }

          @Override
          public String toString() {
            return "reject2Count";
          }
        });
    statsToGet.add(
        new StatsHelper.SimpleStatsGetter() {
          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            return StatsHelper.getStatsOn(liveNodes, Node::getMsgReceived);
          }

          @Override
          public String toString() {
            return "getMsgReceived";
          }
        });

    Predicate<Paxos> finalCheck =
        paxos -> {
          Integer val = null;
          for (ProposerNode pn : paxos.proposers) {
            if (val == null) {
              val = pn.valueAccepted;
            } else {
              if (!val.equals(pn.valueAccepted)) {
                return false;
              }
            }
          }
          return true;
        };

    RunMultipleTimes<Paxos> rmt = new RunMultipleTimes<>(this, 10, 5000, statsToGet, finalCheck);
    List<StatsHelper.Stat> res =
        rmt.run(
            protocol -> {
              for (Node n : protocol.network().allNodes) {
                if (n instanceof ProposerNode && n.doneAt == 0) {
                  return true;
                }
              }
              return false;
            });

    StatsHelper.SimpleStats da = (StatsHelper.SimpleStats) res.get(0);
    StatsHelper.SimpleStats to = (StatsHelper.SimpleStats) res.get(1);
    StatsHelper.SimpleStats r1 = (StatsHelper.SimpleStats) res.get(2);
    StatsHelper.SimpleStats r2 = (StatsHelper.SimpleStats) res.get(3);
    StatsHelper.SimpleStats mr = (StatsHelper.SimpleStats) res.get(4);

    System.out.println(
        this
            + ", doneAt=("
            + da
            + "), timeout=("
            + to
            + "), rejectRound1=("
            + r1
            + "), rejectRound2="
            + r2
            + "), msg received=("
            + mr
            + ")");
  }

  public static void main(String... args) {
    Paxos p = new Paxos(new PaxosParameters());
    p.play();
  }
}
