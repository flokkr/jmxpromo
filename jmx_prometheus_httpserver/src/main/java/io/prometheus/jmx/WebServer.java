package io.prometheus.jmx;

import java.io.File;
import java.net.InetSocketAddress;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;

public class WebServer {

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: WebServer <[hostname:]port> <yaml configuration file>");
      System.exit(1);
    }

    String[] hostnamePort = args[0].split(":");
    int port;
    InetSocketAddress socket;

    if (hostnamePort.length == 2) {
      port = Integer.parseInt(hostnamePort[1]);
      socket = new InetSocketAddress(hostnamePort[0], port);
    } else {
      port = Integer.parseInt(hostnamePort[0]);
      socket = new InetSocketAddress(port);
    }
    Config config = Config.from(new File(args[1]));
    new JmxCollector(config).register();
    new HTTPServer(socket, CollectorRegistry.defaultRegistry);
  }
}
