package net.consensys.wittgenstein.core;

import java.util.List;
import net.consensys.wittgenstein.core.messages.SendMessage;

/** Rest API to be implemented by a remote service */
public interface External {

  <TN extends Node> List<SendMessage> receive(EnvelopeInfo<TN> ei);
}
