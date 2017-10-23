package io.prometheus.jmx;

import io.prometheus.client.Collector;

import java.util.ArrayList;
import java.util.regex.Pattern;

public class Rule {
  Pattern pattern;
  String name;
  String value;
  Double valueFactor = 1.0;
  String help;
  boolean attrNameSnakeCase;
  Collector.Type type = Collector.Type.UNTYPED;
  ArrayList<String> labelNames;
  ArrayList<String> labelValues;
}
