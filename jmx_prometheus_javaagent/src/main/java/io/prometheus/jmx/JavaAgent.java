package io.prometheus.jmx;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.common.net.HostAndPort;
import com.orbitz.consul.Consul;
import com.sun.tools.doclets.formats.html.SourceToHTMLConverter;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

public class JavaAgent {

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
        Config.class.getDeclaredField(argument).set(config, arguments.get(argument));
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

    try {
      if (config.consulHost != null) {
        final Consul consul = Consul.builder().withHostAndPort(HostAndPort.fromParts(config.consulHost, config.consulPort)).build();
        final String serviceId = "jmxexporter-" + UUID.randomUUID();
        URL url = new URL("http://" + socketAddress.getHostName() + ":" + socketAddress.getPort());
        consul.agentClient().register(config.port, url, 10, "jmxexporter", serviceId);
        Runtime.getRuntime().addShutdownHook(new Thread() {
          public void run() {
            if (serviceId != null) {
              consul.agentClient().deregister(serviceId);
            }
          }
        });
      }
    } catch (
        Exception ex)

    {
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
