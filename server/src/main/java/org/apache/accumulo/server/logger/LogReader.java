/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.logger;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.accumulo.core.cli.Help;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.file.FileUtil;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.tabletserver.log.DfsLogger;
import org.apache.accumulo.server.tabletserver.log.MultiReader;
import org.apache.accumulo.server.trace.TraceFileSystem;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class LogReader {

  static class Opts extends Help {
    @Parameter(names = "-r", description = "print only mutations associated with the given row")
    String row;
    @Parameter(names = "-m", description = "limit the number of mutations printed per row")
    int maxMutations = 5;
    @Parameter(names = "-t", description = "print only mutations that fall within the given key extent")
    String extent;
    @Parameter(names = "-p", description = "search for a row that matches the given regex")
    String regexp;
    @Parameter(description = "<logfile> { <logfile> ... }")
    List<String> files = new ArrayList<String>();
  }

  /**
   * Dump a Log File (Map or Sequence) to stdout. Will read from HDFS or local file system.
   *
   * @param args
   *          - first argument is the file to print
   * @throws IOException
   */
  public static void main(String[] args) throws IOException {
    Opts opts = new Opts();
    opts.parseArgs(LogReader.class.getName(), args);
    Configuration conf = CachedConfiguration.getInstance();
    FileSystem fs = TraceFileSystem.wrap(FileUtil.getFileSystem(conf, ServerConfiguration.getSiteConfiguration()));
    FileSystem local = TraceFileSystem.wrap(FileSystem.getLocal(conf));

    Matcher rowMatcher = null;
    KeyExtent ke = null;
    Text row = null;
    if (opts.files.isEmpty()) {
      new JCommander(opts).usage();
      return;
    }
    if (opts.row != null)
      row = new Text(opts.row);
    if (opts.extent != null) {
      String sa[] = opts.extent.split(";");
      ke = new KeyExtent(new Text(sa[0]), new Text(sa[1]), new Text(sa[2]));
    }
    if (opts.regexp != null) {
      Pattern pattern = Pattern.compile(opts.regexp);
      rowMatcher = pattern.matcher("");
    }

    Set<Integer> tabletIds = new HashSet<Integer>();

    for (String file : opts.files) {

      Map<String, String> meta = new HashMap<String, String>();
      Path path = new Path(file);
      LogFileKey key = new LogFileKey();
      LogFileValue value = new LogFileValue();

      if (fs.isFile(path)) {
        // read log entries from a simple hdfs file
        FSDataInputStream f = DfsLogger.readHeader(fs, path, meta);
        try {
          while (true) {
            try {
              key.readFields(f);
              value.readFields(f);
            } catch (EOFException ex) {
              break;
            }
            printLogEvent(key, value, row, rowMatcher, ke, tabletIds, opts.maxMutations);
          }
        } finally {
          f.close();
        }
      } else if (local.isFile(path)) {
        // read log entries from a simple file
        FSDataInputStream f = DfsLogger.readHeader(fs, path, meta);
        try {
          while (true) {
            try {
              key.readFields(f);
              value.readFields(f);
            } catch (EOFException ex) {
              break;
            }
            printLogEvent(key, value, row, rowMatcher, ke, tabletIds, opts.maxMutations);
          }
        } finally {
          f.close();
        }
      } else {
        // read the log entries sorted in a map file
        MultiReader input = new MultiReader(fs, conf, file);
        while (input.next(key, value)) {
          printLogEvent(key, value, row, rowMatcher, ke, tabletIds, opts.maxMutations);
        }
      }
    }
  }

  public static void printLogEvent(LogFileKey key, LogFileValue value, Text row, Matcher rowMatcher, KeyExtent ke, Set<Integer> tabletIds, int maxMutations) {

    if (ke != null) {
      if (key.event == LogEvents.DEFINE_TABLET) {
        if (key.tablet.equals(ke)) {
          tabletIds.add(key.tid);
        } else {
          return;
        }
      } else if (!tabletIds.contains(key.tid)) {
        return;
      }
    }

    if (row != null || rowMatcher != null) {
      if (key.event == LogEvents.MUTATION || key.event == LogEvents.MANY_MUTATIONS) {
        boolean found = false;
        for (Mutation m : value.mutations) {
          if (row != null && new Text(m.getRow()).equals(row)) {
            found = true;
            break;
          }

          if (rowMatcher != null) {
            rowMatcher.reset(new String(m.getRow()));
            if (rowMatcher.matches()) {
              found = true;
              break;
            }
          }
        }

        if (!found)
          return;
      } else {
        return;
      }

    }

    System.out.println(key);
    System.out.println(LogFileValue.format(value, maxMutations));
  }

}
