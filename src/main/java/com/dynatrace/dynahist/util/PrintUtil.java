/*
 * Copyright 2020-2021 Dynatrace LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dynatrace.dynahist.util;

import com.dynatrace.dynahist.Histogram;
import com.dynatrace.dynahist.bin.BinIterator;

import java.util.Locale;

public final class PrintUtil {

  private PrintUtil() {}

  public static String print(Histogram histogram) {

    Preconditions.checkArgument(histogram != null);
    Preconditions.checkArgument(histogram.getTotalCount() != 0);

    BinIterator iterator = histogram.getFirstNonEmptyBin();
    StringBuilder result =
        new StringBuilder(
            String.format(
                (Locale) null,
                "%24.17E - %24.17E : %19d\n",
                iterator.getLowerBound(),
                iterator.getUpperBound(),
                iterator.getBinCount()));
    while (!iterator.isLastNonEmptyBin()) {
      iterator.next();
      result.append(
          String.format(
              (Locale) null,
              "%24.17E - %24.17E : %19d\n",
              iterator.getLowerBound(),
              iterator.getUpperBound(),
              iterator.getBinCount()));
    }
    return result.toString();
  }

  public static String prettyPrint(Histogram histogram) {
    Preconditions.checkArgument(histogram != null);
    Preconditions.checkArgument(histogram.getTotalCount() != 0);

    BinIterator iterator = histogram.getFirstNonEmptyBin();
    StringBuilder temp = new StringBuilder();
    for (int i = 0; i < iterator.getBinCount(); ++i) {
      temp.append('*');
    }
    StringBuilder result =
        new StringBuilder(
            String.format(
                (Locale) null,
                "%24.17E - %24.17E : %s\n",
                iterator.getLowerBound(),
                iterator.getUpperBound(),
                temp));
    while (!iterator.isLastNonEmptyBin()) {
      iterator.next();
      for (int i = 0; i < iterator.getBinCount(); ++i) {
        temp.append('*');
      }
      result.append(
          String.format(
              (Locale) null,
              "%24.17E - %24.17E : %s\n",
              iterator.getLowerBound(),
              iterator.getUpperBound(),
              temp));
    }
    return result.toString();
  }

  public static String printHtml(Histogram histogram) {
    if (histogram == null || histogram.getTotalCount() == 0L) {
      return "";
    }
    BinIterator iterator = histogram.getFirstNonEmptyBin();
    StringBuilder buf = new StringBuilder(2048);
    buf.append("<html><head></head><body><table border=1 cellpadding=4>\n");
    buf.append("<tr class='header'><td>Interval</td><td>Count</td></tr>\n");
    buf.append(
        String.format(
            "<tr><td>%.12E - %.12E</td><td align='right'>%d</td>\n",
            iterator.getLowerBound(),
            iterator.getUpperBound(),
            iterator.getBinCount()));
    while (!iterator.isLastNonEmptyBin()) {
      iterator.next();
      buf.append(
          String.format(
              "<tr><td>%.12E - %.12E</td><td align='right'>%d</td>\n",
              iterator.getLowerBound(),
              iterator.getUpperBound(),
              iterator.getBinCount()));
    }
    buf.append("</table></body></html>\n");
    return buf.toString();
  }
}
