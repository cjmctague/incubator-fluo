/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.cluster.runner;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.common.annotations.VisibleForTesting;
import io.fluo.api.client.FluoClient;
import io.fluo.api.client.FluoFactory;
import io.fluo.api.client.Snapshot;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.api.config.ScannerConfiguration;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.api.data.Span;
import io.fluo.api.exceptions.FluoException;
import io.fluo.api.iterator.ColumnIterator;
import io.fluo.api.iterator.RowIterator;
import io.fluo.cluster.util.FluoInstall;
import io.fluo.core.impl.Environment;
import io.fluo.core.impl.Notification;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for running a Fluo application
 */
public abstract class AppRunner {

  private static final Logger log = LoggerFactory.getLogger(AppRunner.class);
  private static final long MIN_SLEEP_SEC = 10;
  private static final long MAX_SLEEP_SEC = 300;

  private String scriptName;

  public AppRunner(String scriptName) {
    this.scriptName = scriptName;
  }

  public static ScannerConfiguration buildScanConfig(ScanOptions options) {
    ScannerConfiguration scanConfig = new ScannerConfiguration();

    if ((options.getExactRow() != null)
        && ((options.getStartRow() != null) || (options.getEndRow() != null) || (options
            .getRowPrefix() != null))) {
      throw new IllegalArgumentException(
          "You cannot specify an exact row with a start/end row or row prefix!");
    }

    if ((options.getRowPrefix() != null)
        && ((options.getStartRow() != null) || (options.getEndRow() != null) || (options
            .getExactRow() != null))) {
      throw new IllegalArgumentException(
          "You cannot specify an prefix row with a start/end row or exact row!");
    }

    // configure span of scanner
    if (options.getExactRow() != null) {
      scanConfig.setSpan(Span.exact(options.getExactRow()));
    } else if (options.getRowPrefix() != null) {
      scanConfig.setSpan(Span.prefix(options.getRowPrefix()));
    } else {
      if ((options.getStartRow() != null) && (options.getEndRow() != null)) {
        scanConfig.setSpan(new Span(options.getStartRow(), true, options.getEndRow(), true));
      } else if (options.getStartRow() != null) {
        scanConfig.setSpan(new Span(Bytes.of(options.getStartRow()), true, Bytes.EMPTY, true));
      } else if (options.getEndRow() != null) {
        scanConfig.setSpan(new Span(Bytes.EMPTY, true, Bytes.of(options.getEndRow()), true));
      }
    }

    // configure columns of scanner
    for (String column : options.getColumns()) {
      String[] colFields = column.split(":");
      if (colFields.length == 1) {
        scanConfig.fetchColumnFamily(Bytes.of(colFields[0]));
      } else if (colFields.length == 2) {
        scanConfig.fetchColumn(Bytes.of(colFields[0]), Bytes.of(colFields[1]));
      } else {
        throw new IllegalArgumentException("Failed to scan!  Column '" + column
            + "' has too many fields (indicated by ':')");
      }
    }

    return scanConfig;
  }

  public long scan(FluoConfiguration config, String[] args) {
    ScanOptions options = new ScanOptions();
    JCommander jcommand = new JCommander(options);
    jcommand.setProgramName(scriptName + " scan <app>");
    try {
      jcommand.parse(args);
    } catch (ParameterException e) {
      System.err.println(e.getMessage());
      jcommand.usage();
      System.exit(-1);
    }

    if (options.help) {
      jcommand.usage();
      System.exit(0);
    }

    // Limit client to retry for only 500ms as user is waiting
    FluoConfiguration sConfig = new FluoConfiguration(config);
    sConfig.setClientRetryTimeout(500);

    System.out.println("Scanning snapshot of data in Fluo '" + sConfig.getApplicationName()
        + "' application.");

    long entriesFound = 0;
    try (FluoClient client = FluoFactory.newClient(sConfig)) {
      try (Snapshot s = client.newSnapshot()) {

        ScannerConfiguration scanConfig = null;
        try {
          scanConfig = buildScanConfig(options);
        } catch (IllegalArgumentException e) {
          System.err.println(e.getMessage());
          System.exit(-1);
        }

        RowIterator iter = s.get(scanConfig);

        if (!iter.hasNext()) {
          System.out.println("\nNo data found\n");
        }

        while (iter.hasNext() && !System.out.checkError()) {
          Map.Entry<Bytes, ColumnIterator> rowEntry = iter.next();
          ColumnIterator citer = rowEntry.getValue();
          while (citer.hasNext() && !System.out.checkError()) {
            Map.Entry<Column, Bytes> colEntry = citer.next();
            System.out.println(rowEntry.getKey() + " " + colEntry.getKey() + "\t"
                + colEntry.getValue());
            entriesFound++;
          }
        }
      } catch (FluoException e) {
        System.out.println("Scan failed - " + e.getMessage());
      }
    }
    return entriesFound;
  }

  // @formatter:off - Due to formatter putting method on one line that is > 100 chars
  private static void appendLib(StringBuilder classpath, String libDirName,
      boolean useLibJarsFormat) {
    // @formatter:on
    File libDir = new File(libDirName);
    if (!libDir.exists()) {
      System.err.println("ERROR - Directory needed for classpath does not exist: " + libDirName);
      System.exit(-1);
    }

    if (useLibJarsFormat) {
      File[] files = libDir.listFiles();
      if (files != null) {
        Arrays.sort(files);
        for (File f : files) {
          if (f.isFile() && f.getName().endsWith(".jar")) {
            if (classpath.length() != 0) {
              classpath.append(",");
            }
            classpath.append(f.getAbsolutePath());
          }
        }
      }
    } else {
      if (classpath.length() != 0) {
        classpath.append(":");
      }
      classpath.append(libDir.getAbsolutePath()).append("/*");
    }
  }

  public void classpath(String fluoHomeDir, String[] args) {
    ClasspathOptions options = new ClasspathOptions();
    JCommander jcommand = new JCommander(options);
    jcommand.setProgramName(scriptName + " classpath");
    try {
      jcommand.parse(args);
    } catch (ParameterException e) {
      System.err.println(e.getMessage());
      jcommand.usage();
      System.exit(-1);
    }

    if (options.help) {
      jcommand.usage();
      System.exit(0);
    }

    StringBuilder classpath = new StringBuilder();

    appendLib(classpath, fluoHomeDir + "/lib/fluo-client", options.getLibJars());
    if (options.getAccumulo()) {
      appendLib(classpath, fluoHomeDir + "/lib/accumulo", options.getLibJars());
    }
    if (options.getZookeepers()) {
      appendLib(classpath, fluoHomeDir + "/lib/zookeeper", options.getLibJars());
    }
    if (options.getHadoop()) {
      appendLib(classpath, fluoHomeDir + "/lib/hadoop-client", options.getLibJars());
    }

    System.out.println(classpath.toString());
  }

  private long calculateSleep(long notifyCount, long numWorkers) {
    long sleep = notifyCount / numWorkers / 100;
    if (sleep < MIN_SLEEP_SEC) {
      return MIN_SLEEP_SEC;
    } else if (sleep > MAX_SLEEP_SEC) {
      return MAX_SLEEP_SEC;
    }
    return sleep;
  }

  @VisibleForTesting
  public long countNotifications(Environment env) {
    Scanner scanner = null;
    try {
      scanner = env.getConnector().createScanner(env.getTable(), env.getAuthorizations());
    } catch (TableNotFoundException e) {
      log.error("An exception was thrown -", e);
      throw new FluoException(e);
    }

    Notification.configureScanner(scanner);

    long count = 0;
    for (Iterator<Map.Entry<Key, Value>> iterator = scanner.iterator(); iterator.hasNext(); iterator
        .next()) {
      count++;
    }
    return count;
  }

  public void waitUntilFinished(FluoConfiguration config) {
    FluoConfiguration waitConfig = new FluoConfiguration(config);
    waitConfig.setClientRetryTimeout(500);
    try (Environment env = new Environment(waitConfig)) {
      log.info("The wait command will exit when all notifications are processed");
      while (true) {
        long ts1 = env.getSharedResources().getOracleClient().getTimestamp();
        long ntfyCount = countNotifications(env);
        long ts2 = env.getSharedResources().getOracleClient().getTimestamp();
        if (ntfyCount == 0 && ts1 == (ts2 - 1)) {
          log.info("All processing has finished!");
          break;
        }

        try {
          long sleepSec = calculateSleep(ntfyCount, waitConfig.getWorkerInstances());
          log.info("{} notifications are still outstanding.  Will try again in {} seconds...",
              ntfyCount, sleepSec);
          Thread.sleep(1000 * sleepSec);
        } catch (InterruptedException e) {
          log.error("Sleep was interrupted!  Exiting...");
          System.exit(-1);
        }
      }
    } catch (FluoException e) {
      log.error(e.getMessage());
      System.exit(-1);
    } catch (Exception e) {
      log.error("An exception was thrown -", e);
      System.exit(-1);
    }
  }
}
