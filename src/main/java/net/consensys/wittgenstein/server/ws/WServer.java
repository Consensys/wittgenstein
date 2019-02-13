package net.consensys.wittgenstein.server.ws;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.*;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.protocols.P2PFlood;
import net.consensys.wittgenstein.server.IServer;
import net.consensys.wittgenstein.server.Server;
import net.consensys.wittgenstein.server.WParameter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@EnableAutoConfiguration
@RequestMapping("/w")
public class WServer implements IServer {
  private Server server = new Server();

  @GetMapping(value = "/nodes")
  @Override
  public List<? extends Node> getNodeInfo() {
    return server.getNodeInfo();
  }

  @GetMapping(value = "/time")
  @Override
  public int getTime() {
    return server.getTime();
  }

  @Override
  public void init(String fullClassName, WParameter parameters) {}

  @GetMapping(value = "/protocols")
  @Override
  public List<String> getProtocols() {
    return server.getProtocols();
  }

  @GetMapping(value = "/protocols/{fullClassName}")
  @Override
  public WParameter getProtocolParameters(@PathVariable("fullClassName") String fullClassName) {
    WParameter p = server.getProtocolParameters(fullClassName);
    return p;
  }

  @PostMapping(value = "/init/{fullClassName}")
  public void init(@PathVariable("fullClassName") String fullClassName,
      @RequestBody P2PFlood.P2PFloodParameters parameters) { // TODO!!!!!!
    server.init(fullClassName, parameters);
  }

  @PostMapping(value = "/run/{ms}")
  @Override
  public void runMs(@PathVariable("ms") int ms) {
    server.runMs(ms);
  }

  /**
   * We're mapping all fields in the parameters, not taking into account the getters/setters
   */
  @SuppressWarnings("unused")
  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.configure(MapperFeature.DEFAULT_VIEW_INCLUSION, true);
    mapper.configure(SerializationFeature.WRAP_ROOT_VALUE, true);
    mapper.configure(DeserializationFeature.UNWRAP_ROOT_VALUE, true);

    mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.OBJECT_AND_NON_CONCRETE);
    return mapper;
  }

  public static void main(String... args) {
    SpringApplication.run(WServer.class, args);
  }
}
