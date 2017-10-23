package io.prometheus.jmx;

import io.prometheus.client.Collector;
import io.prometheus.client.Counter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.yaml.snakeyaml.Yaml;

import static java.lang.String.format;

public class JmxCollector extends Collector implements Collector.Describable {


  private static final Logger LOGGER = Logger.getLogger(JmxCollector.class.getName());

  private Config config;

  private long createTimeNanoSecs = System.nanoTime();

  private static final Pattern snakeCasePattern = Pattern.compile("([a-z0-9])([A-Z])");

  public JmxCollector(Config config) throws IOException, MalformedObjectNameException {
    this.config = config;
  }

  public JmxCollector(String configString) throws IOException, MalformedObjectNameException {
    this(Config.from(configString));
  }


  class Receiver implements JmxScraper.MBeanReceiver {
    Map<String, MetricFamilySamples> metricFamilySamplesMap =
        new HashMap<String, MetricFamilySamples>();

    private static final char SEP = '_';

    private final Pattern unsafeChars = Pattern.compile("[^a-zA-Z0-9:_]");
    private final Pattern multipleUnderscores = Pattern.compile("__+");

    // [] and () are special in regexes, so swtich to <>.
    private String angleBrackets(String s) {
      return "<" + s.substring(1, s.length() - 1) + ">";
    }

    private String safeName(String s) {
      // Change invalid chars to underscore, and merge underscores.
      return multipleUnderscores.matcher(unsafeChars.matcher(s).replaceAll("_")).replaceAll("_");
    }

    void addSample(MetricFamilySamples.Sample sample, Type type, String help) {
      MetricFamilySamples mfs = metricFamilySamplesMap.get(sample.name);
      if (mfs == null) {
        // JmxScraper.MBeanReceiver is only called from one thread,
        // so there's no race here.
        mfs = new MetricFamilySamples(sample.name, type, help, new ArrayList<MetricFamilySamples.Sample>());
        metricFamilySamplesMap.put(sample.name, mfs);
      }
      mfs.samples.add(sample);
    }

    private void defaultExport(
        String domain,
        LinkedHashMap<String, String> beanProperties,
        LinkedList<String> attrKeys,
        String attrName,
        String attrType,
        String help,
        Object value,
        Type type) {
      StringBuilder name = new StringBuilder();
      name.append(domain);
      if (beanProperties.size() > 0) {
        name.append(SEP);
        name.append(beanProperties.values().iterator().next());
      }
      for (String k : attrKeys) {
        name.append(SEP);
        name.append(k);
      }
      name.append(SEP);
      name.append(attrName);
      String fullname = safeName(name.toString());

      if (config.lowercaseOutputName) {
        fullname = fullname.toLowerCase();
      }

      List<String> labelNames = new ArrayList<String>();
      List<String> labelValues = new ArrayList<String>();
      if (beanProperties.size() > 1) {
        Iterator<Map.Entry<String, String>> iter = beanProperties.entrySet().iterator();
        // Skip the first one, it's been used in the name.
        iter.next();
        while (iter.hasNext()) {
          Map.Entry<String, String> entry = iter.next();
          String labelName = safeName(entry.getKey());
          if (config.lowercaseOutputLabelNames) {
            labelName = labelName.toLowerCase();
          }
          labelNames.add(labelName);
          labelValues.add(entry.getValue());
        }
      }

      addSample(new MetricFamilySamples.Sample(fullname, labelNames, labelValues, ((Number) value).doubleValue()),
          type, help);
    }

    public void recordBean(
        String domain,
        LinkedHashMap<String, String> beanProperties,
        LinkedList<String> attrKeys,
        String attrName,
        String attrType,
        String attrDescription,
        Object beanValue) {

      String beanName = domain + angleBrackets(beanProperties.toString()) + angleBrackets(attrKeys.toString());
      // attrDescription tends not to be useful, so give the fully qualified name too.
      String help = attrDescription + " (" + beanName + attrName + ")";
      String attrNameSnakeCase = snakeCasePattern.matcher(attrName).replaceAll("$1_$2").toLowerCase();

      for (Rule rule : config.rules) {
        Matcher matcher = null;
        String matchName = beanName + (rule.attrNameSnakeCase ? attrNameSnakeCase : attrName);
        if (rule.pattern != null) {
          matcher = rule.pattern.matcher(matchName + ": " + beanValue);
          if (!matcher.matches()) {
            continue;
          }
        }

        Number value;
        if (rule.value != null && !rule.value.isEmpty()) {
          String val = matcher.replaceAll(rule.value);

          try {
            beanValue = Double.valueOf(val);
          } catch (NumberFormatException e) {
            LOGGER.fine("Unable to parse configured value '" + val + "' to number for bean: " + beanName + attrName + ": " + beanValue);
            return;
          }
        }
        if (beanValue instanceof Number) {
          value = ((Number) beanValue).doubleValue() * rule.valueFactor;
        } else if (beanValue instanceof Boolean) {
          value = (Boolean) beanValue ? 1 : 0;
        } else {
          LOGGER.fine("Ignoring unsupported bean: " + beanName + attrName + ": " + beanValue);
          return;
        }

        // If there's no name provided, use default export format.
        if (rule.name == null) {
          defaultExport(domain, beanProperties, attrKeys, rule.attrNameSnakeCase ? attrNameSnakeCase : attrName, attrType, help, value, rule.type);
          return;
        }

        // Matcher is set below here due to validation in the constructor.
        String name = safeName(matcher.replaceAll(rule.name));
        if (name.isEmpty()) {
          return;
        }
        if (config.lowercaseOutputName) {
          name = name.toLowerCase();
        }

        // Set the help.
        if (rule.help != null) {
          help = matcher.replaceAll(rule.help);
        }

        // Set the labels.
        ArrayList<String> labelNames = new ArrayList<String>();
        ArrayList<String> labelValues = new ArrayList<String>();
        if (rule.labelNames != null) {
          for (int i = 0; i < rule.labelNames.size(); i++) {
            final String unsafeLabelName = rule.labelNames.get(i);
            final String labelValReplacement = rule.labelValues.get(i);
            try {
              String labelName = safeName(matcher.replaceAll(unsafeLabelName));
              String labelValue = matcher.replaceAll(labelValReplacement);
              if (config.lowercaseOutputLabelNames) {
                labelName = labelName.toLowerCase();
              }
              if (!labelName.isEmpty() && !labelValue.isEmpty()) {
                labelNames.add(labelName);
                labelValues.add(labelValue);
              }
            } catch (Exception e) {
              throw new RuntimeException(
                  format("Matcher '%s' unable to use: '%s' value: '%s'", matcher, unsafeLabelName, labelValReplacement), e);
            }
          }
        }

        // Add to samples.
        LOGGER.fine("add metric sample: " + name + " " + labelNames + " " + labelValues + " " + value.doubleValue());
        addSample(new MetricFamilySamples.Sample(name, labelNames, labelValues, value.doubleValue()), rule.type, help);
        return;
      }
    }

  }

  public List<MetricFamilySamples> collect() {
    if (
        config.isChanged()) {
      LOGGER.fine("Configuration file changed, reloading...");
      config.reload();
    }


    Receiver receiver = new Receiver();
    JmxScraper scraper = new JmxScraper(config.jmxUrl, config.username, config.password, config.ssl, config.whitelistObjectNames, config.blacklistObjectNames, receiver);
    long start = System.nanoTime();
    double error = 0;
    if ((config.startDelaySeconds > 0) &&
        ((start - createTimeNanoSecs) / 1000000000L < config.startDelaySeconds)) {
      throw new IllegalStateException("JMXCollector waiting for startDelaySeconds");
    }
    try {
      scraper.doScrape();
    } catch (Exception e) {
      error = 1;
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      LOGGER.severe("JMX scrape failed: " + sw.toString());
    }
    List<MetricFamilySamples> mfsList = new ArrayList<MetricFamilySamples>();
    mfsList.addAll(receiver.metricFamilySamplesMap.values());
    List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
    samples.add(new MetricFamilySamples.Sample(
        "jmx_scrape_duration_seconds", new ArrayList<String>(), new ArrayList<String>(), (System.nanoTime() - start) / 1.0E9));
    mfsList.add(new MetricFamilySamples("jmx_scrape_duration_seconds", Type.GAUGE, "Time this JMX scrape took, in seconds.", samples));

    samples = new ArrayList<MetricFamilySamples.Sample>();
    samples.add(new MetricFamilySamples.Sample(
        "jmx_scrape_error", new ArrayList<String>(), new ArrayList<String>(), error));
    mfsList.add(new MetricFamilySamples("jmx_scrape_error", Type.GAUGE, "Non-zero if this scrape failed.", samples));
    return mfsList;
  }

  public List<MetricFamilySamples> describe() {
    List<MetricFamilySamples> sampleFamilies = new ArrayList<MetricFamilySamples>();
    sampleFamilies.add(new MetricFamilySamples("jmx_scrape_duration_seconds", Type.GAUGE, "Time this JMX scrape took, in seconds.", new ArrayList<MetricFamilySamples.Sample>()));
    sampleFamilies.add(new MetricFamilySamples("jmx_scrape_error", Type.GAUGE, "Non-zero if this scrape failed.", new ArrayList<MetricFamilySamples.Sample>()));
    return sampleFamilies;
  }


}
