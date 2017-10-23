package io.prometheus.jmx;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.common.net.HostAndPort;
import com.orbitz.consul.Consul;
import com.orbitz.consul.model.catalog.CatalogRegistration;
import com.orbitz.consul.model.catalog.ImmutableCatalogRegistration;
import com.orbitz.consul.model.health.ImmutableService;
import com.orbitz.consul.model.health.Service;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

public class JavaAgent {

  private static final String consulTag = "jmxexporter";
  static HTTPServer server;

  public static void agentmain(String agentArgument, Instrumentation instrumentation) throws Exception {
    premain(agentArgument, instrumentation);
  }


  public static void premain(String agentArgument, Instrumentation instrumentation) throws Exception {
    String[] args = agentArgument.split(":");

    Map<String, String> arguments = new HashMap<String, String>();
    for (String arg : args) {
      String[] parts = arg.split("=");
      if (parts.length != 2) {
        throw new RuntimeException("Wrong argument format " + arg);
      } else {
        arguments.put(parts[0], parts[1]);
      }
    }

    Config config = new Config();
    if (arguments.containsKey("configfile")) {
      config = Config.from(new File(arguments.get("configfile")));
    }
    for (String argument : arguments.keySet()) {
      try {
        //TODO cast to boolean or int
        Field field = Config.class.getDeclaredField(argument);
        if (field.getType().equals(String.class)) {
          field.set(config, arguments.get(argument));
        } else if (field.getType().equals(int.class) || field.getType().equals(Integer.class)) {
          field.set(config, Integer.parseInt(arguments.get(argument)));
        }

      } catch (NoSuchFieldException ex) {
        System.out.println("Invalid configuration key: " + argument);
      }
    }


    //workaround to get a randomized port
    if (config.port == 0) {
      ServerSocket serverSocket = new ServerSocket(0);
      config.port = serverSocket.getLocalPort();
      serverSocket.close();
    }

    InetSocketAddress socketAddress = getInetSocketAddress(config.host, config.port);
    new JmxCollector(config).register();
    DefaultExports.initialize();
    server = new HTTPServer(socketAddress, CollectorRegistry.defaultRegistry);


    if (config.consulHost != null) {
      registerToConsul(config, socketAddress);
    }


  }


  private static void registerToConsul(Config config, InetSocketAddress socketAddress) {
    try {
      final Consul consul = Consul.builder().withHostAndPort(HostAndPort.fromParts(config.consulHost, config.consulPort)).build();
      final String serviceId = consulTag + "-" + UUID.randomUUID();
      System.out.println("Registering consul service " + serviceId);
      if (!config.consulMode.equals("agent")) {
        Service service = ImmutableService.builder()
            .id(serviceId)
            .service(consulTag)
            .port(socketAddress.getPort())
            .address(InetAddress.getLocalHost().getHostName())
            .build();

        CatalogRegistration registration = ImmutableCatalogRegistration.builder()
            .datacenter("dc1")
            .node("node1")
            .service(service)
            .address(service.getAddress())
            .build();
        consul.catalogClient().register(registration);
      } else {
        URL url = new URL("http://" + socketAddress.getHostName() + ":" + socketAddress.getPort());
        consul.agentClient().register(config.port, url, 10, consulTag, serviceId);
      }

      Runtime.getRuntime().addShutdownHook(new Thread() {

        public void run() {
          System.out.println("Deregistering consul service: " + serviceId);
          if (serviceId != null) {
            consul.agentClient().deregister(serviceId);
          }
        }
      });
    } catch (Exception ex) {
      System.out.println("Consul is not available.");
      ex.printStackTrace();
    }
  }

  private static InetSocketAddress getInetSocketAddress(String hostname, int port) {
    InetSocketAddress socketAddress;
    if (hostname != null) {
      socketAddress = new InetSocketAddress(hostname, port);
    } else {
      socketAddress = new InetSocketAddress(port);
    }
    return socketAddress;
  }

}
