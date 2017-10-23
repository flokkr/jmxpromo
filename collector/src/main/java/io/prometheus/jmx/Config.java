package io.prometheus.jmx;

import io.prometheus.client.Collector;
import io.prometheus.client.Counter;
import org.yaml.snakeyaml.Yaml;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class Config {

  private static final Logger LOGGER = Logger.getLogger(Config.class.getName());

  static final Counter configReloadSuccess = Counter.build()
      .name("jmx_config_reload_success_total")
      .help("Number of times configuration have successfully been reloaded.").register();

  static final Counter configReloadFailure = Counter.build()
      .name("jmx_config_reload_failure_total")
      .help("Number of times configuration have failed to be reloaded.").register();

  File configFile;

  long lastUpdate = 0L;
  String host = "0.0.0.0";
  String consulHost;
  int consulPort = 8500;
  int port = 0;
  Integer startDelaySeconds = 0;
  String jmxUrl = "";
  String username = "";
  String password = "";
  boolean ssl = false;
  boolean lowercaseOutputName;
  boolean lowercaseOutputLabelNames;
  List<ObjectName> whitelistObjectNames = new ArrayList<ObjectName>();
  List<ObjectName> blacklistObjectNames = new ArrayList<ObjectName>();
  ArrayList<Rule> rules = new ArrayList<Rule>();
  private boolean changed;


  public static Config from(File configFile) {
    try {
      FileReader fr = new FileReader(configFile);
      Map<String, Object> newYamlConfig = (Map<String, Object>) new Yaml().load(fr);
      Config config = new Config();
      from(config, newYamlConfig);
      return config;
    } catch (Exception ex) {
      throw new RuntimeException("Error on loading " + configFile.getAbsolutePath(), ex);
    }
  }


  public static Config from(String configString) {
    try {
      Map<String, Object> newYamlConfig = (Map<String, Object>) new Yaml().load(configString);
      Config config = new Config();
      from(config, newYamlConfig);
      return config;
    } catch (Exception ex) {
      throw new RuntimeException("Error on loading config from string: " + configString, ex);
    }
  }

  public static void from(Config cfg, Map<String, Object> yamlConfig) throws MalformedObjectNameException {

    if (yamlConfig == null) {  // Yaml config empty, set config to empty map.
      yamlConfig = new HashMap<String, Object>();
    }

    if (yamlConfig.containsKey("startDelaySeconds")) {
      try {
        cfg.startDelaySeconds = (Integer) yamlConfig.get("startDelaySeconds");
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid number provided for startDelaySeconds", e);
      }
    }
    if (yamlConfig.containsKey("hostPort")) {
      if (yamlConfig.containsKey("jmxUrl")) {
        throw new IllegalArgumentException("At most one of hostPort and jmxUrl must be provided");
      }
      cfg.jmxUrl = "service:jmx:rmi:///jndi/rmi://" + (String) yamlConfig.get("hostPort") + "/jmxrmi";
    } else if (yamlConfig.containsKey("jmxUrl")) {
      cfg.jmxUrl = (String) yamlConfig.get("jmxUrl");
    }

    if (yamlConfig.containsKey("username")) {
      cfg.username = (String) yamlConfig.get("username");
    }

    if (yamlConfig.containsKey("password")) {
      cfg.password = (String) yamlConfig.get("password");
    }

    if (yamlConfig.containsKey("ssl")) {
      cfg.ssl = (Boolean) yamlConfig.get("ssl");
    }

    if (yamlConfig.containsKey("lowercaseOutputName")) {
      cfg.lowercaseOutputName = (Boolean) yamlConfig.get("lowercaseOutputName");
    }

    if (yamlConfig.containsKey("lowercaseOutputLabelNames")) {
      cfg.lowercaseOutputLabelNames = (Boolean) yamlConfig.get("lowercaseOutputLabelNames");
    }

    if (yamlConfig.containsKey("whitelistObjectNames")) {
      List<Object> names = (List<Object>) yamlConfig.get("whitelistObjectNames");
      for (Object name : names) {
        cfg.whitelistObjectNames.add(new ObjectName((String) name));
      }
    } else {
      cfg.whitelistObjectNames.add(null);
    }

    if (yamlConfig.containsKey("blacklistObjectNames")) {
      List<Object> names = (List<Object>) yamlConfig.get("blacklistObjectNames");
      for (Object name : names) {
        cfg.blacklistObjectNames.add(new ObjectName((String) name));
      }
    }

    if (yamlConfig.containsKey("rules")) {
      List<Map<String, Object>> configRules = (List<Map<String, Object>>) yamlConfig.get("rules");
      for (Map<String, Object> ruleObject : configRules) {
        Map<String, Object> yamlRule = ruleObject;
        Rule rule = new Rule();
        cfg.rules.add(rule);
        if (yamlRule.containsKey("pattern")) {
          rule.pattern = Pattern.compile("^.*(?:" + (String) yamlRule.get("pattern") + ").*$");
        }
        if (yamlRule.containsKey("name")) {
          rule.name = (String) yamlRule.get("name");
        }
        if (yamlRule.containsKey("value")) {
          rule.value = String.valueOf(yamlRule.get("value"));
        }
        if (yamlRule.containsKey("valueFactor")) {
          String valueFactor = String.valueOf(yamlRule.get("valueFactor"));
          try {
            rule.valueFactor = Double.valueOf(valueFactor);
          } catch (NumberFormatException e) {
            // use default value
          }
        }
        if (yamlRule.containsKey("attrNameSnakeCase")) {
          rule.attrNameSnakeCase = (Boolean) yamlRule.get("attrNameSnakeCase");
        }
        if (yamlRule.containsKey("type")) {
          rule.type = Collector.Type.valueOf((String) yamlRule.get("type"));
        }
        if (yamlRule.containsKey("help")) {
          rule.help = (String) yamlRule.get("help");
        }
        if (yamlRule.containsKey("labels")) {
          TreeMap labels = new TreeMap((Map<String, Object>) yamlRule.get("labels"));
          rule.labelNames = new ArrayList<String>();
          rule.labelValues = new ArrayList<String>();
          for (Map.Entry<String, Object> entry : (Set<Map.Entry<String, Object>>) labels.entrySet()) {
            rule.labelNames.add(entry.getKey());
            rule.labelValues.add((String) entry.getValue());
          }
        }

        // Validation.
        if ((rule.labelNames != null || rule.help != null) && rule.name == null) {
          throw new IllegalArgumentException("Must provide name, if help or labels are given: " + yamlRule);
        }
        if (rule.name != null && rule.pattern == null) {
          throw new IllegalArgumentException("Must provide pattern, if name is given: " + yamlRule);
        }
      }
    } else {
      // Default to a single default rule.
      cfg.rules.add(new Rule());
    }

  }

  public void reloadConfig() {
    try {
      FileReader fr = new FileReader(configFile);

      try {
        Map<String, Object> newYamlConfig = (Map<String, Object>) new Yaml().load(fr);
        from(this, newYamlConfig);
        this.lastUpdate = configFile.lastModified();
        configReloadSuccess.inc();
      } catch (Exception e) {
        LOGGER.severe("Configuration reload failed: " + e.toString());
        configReloadFailure.inc();
      } finally {
        fr.close();
      }

    } catch (IOException e) {
      LOGGER.severe("Configuration reload failed: " + e.toString());
      configReloadFailure.inc();
    }
  }

  public boolean isChanged() {
    if (configFile != null) {
      long mtime = configFile.lastModified();
      if (mtime > this.lastUpdate) {
        return true;
      }
    }
    return false;
  }

  public void reload() {

  }

}
