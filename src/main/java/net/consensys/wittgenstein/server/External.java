package net.consensys.wittgenstein.server;

import java.util.List;

import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.Node;

/** Rest API to be implemented by a remote service */
public interface External {

  <TN extends Node> List<SendMessage> receive(EnvelopeInfo<TN> ei);
}
