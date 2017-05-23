package io.prometheus.jmx;

import java.io.IOException;
import java.lang.management.ManagementFactory;

public class TestApplication {
    public static void main(String[] args) throws IOException {
        System.out.println(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        System.out.flush();
        System.in.read();
        System.exit(0);
    }
}
