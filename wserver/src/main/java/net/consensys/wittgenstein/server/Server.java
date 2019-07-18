package net.consensys.wittgenstein.server;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.messages.SendMessage;
import net.consensys.wittgenstein.core.utils.Reflects;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;

/** Allows to run the protocols in a (web)server. */
public class Server implements IServer {
  private Protocol protocol;

  public List<? extends Node> getNodeInfo() {
    return protocol.network().allNodes;
  }

  public int getTime() {
    return protocol.network().time;
  }

  private Constructor<?> getConstructor(String fullClassName) {
    Class<?> clazz = Reflects.forName(fullClassName);
    if (clazz == null) {
      throw new IllegalArgumentException("Class not found: " + fullClassName);
    }

    for (Constructor<?> c : clazz.getConstructors()) {
      if (c.getParameterCount() == 1
          && WParameters.class.isAssignableFrom(c.getParameterTypes()[0])) {
        return c;
      }
    }

    throw new IllegalStateException("no constructor in " + fullClassName);
  }

  @Override
  public void init(String fullClassName, WParameters parameters) {
    Constructor<?> c = getConstructor(fullClassName);
    protocol = (Protocol) Reflects.newInstance(c, parameters);
    protocol.init();
  }

  @Override
  public List<String> getProtocols() {
    BeanDefinitionRegistry bdr = new SimpleBeanDefinitionRegistry();
    ClassPathBeanDefinitionScanner s = new ClassPathBeanDefinitionScanner(bdr, false);

    TypeFilter tf = new AssignableTypeFilter(Protocol.class);

    s.addIncludeFilter(tf);
    s.setIncludeAnnotationConfig(false);
    s.scan("net.consensys");

    String[] beans = bdr.getBeanDefinitionNames();
    return Arrays.stream(beans)
        .map(n -> bdr.getBeanDefinition(n).getBeanClassName())
        .collect(Collectors.toList());
  }

  @Override
  public WParameters getProtocolParameters(String fullClassName) {
    Class<?> clazz = Reflects.forName(fullClassName);
    Constructor<?> bc = null;
    for (Constructor<?> c : clazz.getConstructors()) {
      if (c.getParameterCount() == 1) {
        bc = c;
      }
    }
    if (bc == null) {
      throw new IllegalStateException(
          "no constructor in "
              + fullClassName
              + ", we need a *public* "
              + "constructor taking a subclass of WParameters as unique parameter.");
    }

    Class<?> type = bc.getParameters()[0].getType();
    Constructor<?> cons;
    try {
      cons = type.getConstructor();
    } catch (Exception e) {
      throw new IllegalStateException(
          "We need a public constructor without parameters for the parameters class." + type, e);
    }

    try {
      return (WParameters) cons.newInstance();
    } catch (Exception e) {
      throw new IllegalStateException("Can't create without parameters " + type, e);
    }
  }

  public List<Class<?>> getParametersName() {
    List<Class<?>> res = new ArrayList<>();
    for (String s : getProtocols()) {
      try {
        WParameters wp = getProtocolParameters(s);
        res.add(wp.getClass());
      } catch (Throwable ignored) {
      }
    }
    return res;
  }

  public static Set<String> getMessageType() {
    BeanDefinitionRegistry bdr = new SimpleBeanDefinitionRegistry();
    ClassPathBeanDefinitionScanner s = new ClassPathBeanDefinitionScanner(bdr, false);
    s.addIncludeFilter(new AssignableTypeFilter(Message.class));

    s.scan("net.consensys");
    String[] beans = bdr.getBeanDefinitionNames();
    return Arrays.stream(beans)
        .map(n -> bdr.getBeanDefinition(n).getBeanClassName())
        .collect(Collectors.toSet());
  }

  @Override
  public void runMs(int ms) {
    protocol.network().runMs(ms);
  }

  @Override
  public void startNode(int nodeId) {
    protocol.network().getNodeById(nodeId).start();
  }

  @Override
  public void stopNode(int nodeId) {
    protocol.network().getNodeById(nodeId).stop();
  }

  @Override
  public void setExternal(int nodeId, String externalServiceFullAddress) {
    External ext;
    if (externalServiceFullAddress == null || externalServiceFullAddress.trim().isEmpty()) {
      ext = new ExternalMockImplementation(protocol.network());
    } else {
      ext = new ExternalRest(externalServiceFullAddress);
    }
    protocol.network().getNodeById(nodeId).setExternal(ext);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <TN extends Node> void sendMessage(SendMessage msg) {
    Network<TN> n = (Network<TN>) protocol.network();
    TN fromN = n.getNodeById(msg.from);
    List<TN> destN = msg.to.stream().map(n::getNodeById).collect(Collectors.toList());
    Message<TN> m = (Message<TN>) msg.message;
    n.send(m, msg.sendTime, fromN, destN, msg.delayBetweenSend);
  }

  @Override
  public Node getNodeInfo(int nodeId) {
    return protocol.network().getNodeById(nodeId);
  }

  @Override
  public List<EnvelopeInfo<?>> getMessages() {
    return protocol.network().msgs.peekMessages();
  }
}
