package net.consensys.wittgenstein.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.External;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.messages.SendMessage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dummy implementation of an external node. It just prints the messages. */
@RestController
@EnableAutoConfiguration
@RequestMapping("/w")
public class ExternalWS implements External {

  @PutMapping(value = "/external_sink")
  @Override
  public <TN extends Node> List<SendMessage> receive(@RequestBody EnvelopeInfo<TN> ei) {
    System.out.println("Received message: " + ei);

    // FloodMessage<?> f = new FloodMessage<>(2000, 10,10);
    // SendMessage m = new SendMessage(0, new ArrayList<>( List.of(1,2,3)), 1200, 1, f);
    // return new ArrayList<>(Collections.singleton(m));
    return Collections.emptyList();
  }

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    return ObjectMapperFactory.objectMapper();
  }
}
