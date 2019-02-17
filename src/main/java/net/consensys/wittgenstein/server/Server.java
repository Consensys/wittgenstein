package net.consensys.wittgenstein.server;

import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.Reflects;
import net.consensys.wittgenstein.protocols.PingPong;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Allows to run the protocols in a (web)server.
 */
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

    for (Constructor<?> c : clazz.getConstructors()) {
      if (c.getParameterCount() == 1
          && WParameter.class.isAssignableFrom(c.getParameterTypes()[0])) {
        return c;
      }
    }

    throw new IllegalStateException("no constructor in " + fullClassName);
  }

  @Override
  public void init(String fullClassName, WParameter parameters) {
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
    s.scan(PingPong.class.getPackage().getName());

    String[] beans = bdr.getBeanDefinitionNames();
    return Arrays.stream(beans).map(n -> bdr.getBeanDefinition(n).getBeanClassName()).collect(
        Collectors.toList());
  }

  @Override
  public WParameter getProtocolParameters(String fullClassName) {
    Class<?> clazz = Reflects.forName(fullClassName);
    Constructor<?> bc = null;
    for (Constructor<?> c : clazz.getConstructors()) {
      if (c.getParameterCount() == 1) {
        bc = c;
      }
    }
    if (bc == null) {
      throw new IllegalStateException("no constructor in " + fullClassName);
    }

    return (WParameter) Reflects.newInstance(bc.getParameters()[0].getType());
  }

  public List<Class<?>> getParametersName() {
    List<Class<?>> res = new ArrayList<>();
    for (String s : getProtocols()) {
      try {
        WParameter wp = getProtocolParameters(s);
        res.add(wp.getClass());
      } catch (Throwable ignored) {
      }

    }
    return res;
  }

  private static List<Package> getProtocolPackages() {
    return List.of(PingPong.class.getPackage(), Node.class.getPackage());
  }

  public static Set<String> getMessageType() {
    BeanDefinitionRegistry bdr = new SimpleBeanDefinitionRegistry();
    ClassPathBeanDefinitionScanner s = new ClassPathBeanDefinitionScanner(bdr, false);
    s.addIncludeFilter(new AssignableTypeFilter(Message.class));

    Set<String> res = new HashSet<>();
    for (Package p : getProtocolPackages()) {
      try {
        s.scan(p.getName());
        String[] beans = bdr.getBeanDefinitionNames();
        res.addAll(
            Arrays.stream(beans).map(n -> bdr.getBeanDefinition(n).getBeanClassName()).collect(
                Collectors.toList()));
      } catch (Throwable ignored) {
      }
    }

    return res;
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


  public static void main(String... args) {
    Server server = new Server();
    List<String> ps = server.getProtocols();
    System.out.println(ps);
    String clazz = "net.consensys.wittgenstein.protocols.P2PFlood";
    WParameter params = server.getProtocolParameters(clazz);
    System.out.println(params);
    server.init(clazz, params);
    server.runMs(100);
    System.out.println(server.protocol);
    System.out.println(server.getNodeInfo());
    System.out.println("" + server.getTime());
  }

}
