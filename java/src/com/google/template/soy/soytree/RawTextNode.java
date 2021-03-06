/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.SourceLocation.Point;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Node representing a contiguous raw text section.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class RawTextNode extends AbstractSoyNode implements StandaloneNode {

  /** The special chars we need to re-escape for toSourceString(). */
  private static final Pattern SPECIAL_CHARS_TO_ESCAPE = Pattern.compile("[\n\r\t{}]");

  /** Map from special char to be re-escaped to its special char tag (for toSourceString()). */
  private static final Map<String, String> SPECIAL_CHAR_TO_TAG =
      ImmutableMap.<String, String>builder()
          .put("\n", "{\\n}")
          .put("\r", "{\\r}")
          .put("\t", "{\\t}")
          .put("{", "{lb}")
          .put("}", "{rb}")
          .build();

  /** The raw text string (after processing of special chars and literal blocks). */
  private final String rawText;

  @Nullable private final SourceOffsets offsets;

  @Nullable private HtmlContext htmlContext;

  /**
   * @param id The id for this node.
   * @param rawText The raw text string.
   * @param sourceLocation The node's source location.
   */
  public RawTextNode(int id, String rawText, SourceLocation sourceLocation) {
    this(id, rawText, sourceLocation, SourceOffsets.fromLocation(sourceLocation, rawText.length()));
  }

  public RawTextNode(
      int id, String rawText, SourceLocation sourceLocation, HtmlContext htmlContext) {
    super(id, sourceLocation);
    checkArgument(!rawText.isEmpty(), "you can't create empty RawTextNodes");
    this.rawText = rawText;
    this.htmlContext = htmlContext;
    this.offsets = SourceOffsets.fromLocation(sourceLocation, rawText.length());
  }

  public RawTextNode(int id, String rawText, SourceLocation sourceLocation, SourceOffsets offsets) {
    super(id, sourceLocation);
    checkArgument(!rawText.isEmpty(), "you can't create empty RawTextNodes");
    this.rawText = rawText;
    this.offsets = offsets;
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private RawTextNode(RawTextNode orig, CopyState copyState) {
    super(orig, copyState);
    this.rawText = orig.rawText;
    this.htmlContext = orig.htmlContext;
    this.offsets = orig.offsets;
  }

  /**
   * Gets the HTML source context (typically tag, attribute value, HTML PCDATA, or plain text) which
   * this node emits in. This is used for incremental DOM codegen.
   */
  public HtmlContext getHtmlContext() {
    return Preconditions.checkNotNull(
        htmlContext, "Cannot access HtmlContext before HtmlTransformVisitor");
  }

  @Override
  public Kind getKind() {
    return Kind.RAW_TEXT_NODE;
  }

  public void setHtmlContext(HtmlContext value) {
    this.htmlContext = value;
  }

  /** Returns the raw text string (after processing of special chars and literal blocks). */
  public String getRawText() {
    return rawText;
  }

  public Point locationOf(int i) {
    checkElementIndex(i, rawText.length(), "index");
    if (offsets == null) {
      return Point.UNKNOWN_POINT;
    }
    return offsets.getPoint(rawText, i);
  }

  /** Returns the source location of the given substring. */
  public SourceLocation substringLocation(int start, int end) {
    checkElementIndex(start, rawText.length(), "start");
    checkArgument(start < end);
    checkArgument(end <= rawText.length());
    if (offsets == null) {
      return getSourceLocation();
    }
    return new SourceLocation(
        getSourceLocation().getFilePath(),
        offsets.getPoint(rawText, start),
        // source locations are inclusive, the end locations should point at the last character
        // in the string, whereas substring is usually specified exclusively, so subtract 1 to make
        // up the difference
        offsets.getPoint(rawText, end - 1));
  }

  /**
   * Returns a new RawTextNode that represents the given {@link String#substring(int, int)} of this
   * raw text node.
   *
   * <p>Unlike {@link String#substring(int, int)} the range must be non-empty
   *
   * @param newId the new node id to use
   * @param start the start location
   * @param end the end location
   */
  public RawTextNode substring(int newId, int start, int end) {
    checkArgument(start >= 0);
    checkArgument(start < end);
    checkArgument(end <= rawText.length());
    if (start == 0 && end == rawText.length()) {
      return this;
    }
    String newText = rawText.substring(start, end);
    SourceOffsets newOffsets = null;
    SourceLocation newLocation = getSourceLocation();
    if (offsets != null) {
      newOffsets = offsets.substring(start, end, rawText);
      newLocation = newOffsets.getSourceLocation(getSourceLocation().getFilePath());
    }
    return new RawTextNode(newId, newText, newLocation, newOffsets);
  }

  /**
   * Concatenates this RawTextNode with the given node (like {@link String#concat}), preserving
   * source location information.
   */
  public RawTextNode concat(int newId, RawTextNode node) {
    checkNotNull(node);
    String newText = rawText.concat(node.getRawText());
    SourceOffsets newOffsets = null;
    SourceLocation newLocation = getSourceLocation().extend(node.getSourceLocation());
    if (offsets != null && node.offsets != null) {
      newOffsets = offsets.concat(node.offsets);
    }
    return new RawTextNode(newId, newText, newLocation, newOffsets);
  }

  @Override
  public String toSourceString() {

    StringBuffer sb = new StringBuffer();

    // Must escape special chars to create valid source text.
    Matcher matcher = SPECIAL_CHARS_TO_ESCAPE.matcher(rawText);
    while (matcher.find()) {
      String specialCharTag = SPECIAL_CHAR_TO_TAG.get(matcher.group());
      matcher.appendReplacement(sb, Matcher.quoteReplacement(specialCharTag));
    }
    matcher.appendTail(sb);

    return sb.toString();
  }

  @Override
  public BlockNode getParent() {
    return (BlockNode) super.getParent();
  }

  @Override
  public RawTextNode copy(CopyState copyState) {
    return new RawTextNode(this, copyState);
  }

  /**
   * A helper object to calculate source location offsets inside of RawTextNodes.
   *
   * <p>Due to how Soy collapses whitespace and uses non-literal tokens for textual content (e.g.
   * {@code literal} commands and formatting commands like {@code {\n}}). It isn't possible to
   * reconstruct the source location of any given character within a sequence of raw text based
   * purely on start/end locations. This class fulfils the gap by tracking offsets where the
   * sourcelocation changes discontinuously.
   */
  public static final class SourceOffsets {

    @Nullable
    static SourceOffsets fromLocation(SourceLocation location, int length) {
      if (!location.isKnown()) {
        // this is lame but a lot of tests construct 'unknown' rawtextnodes
        return null;
      }
      return new SourceOffsets.Builder()
          .add(
              0,
              location.getBeginLine(),
              location.getBeginColumn(),
              location.getEndLine(),
              location.getEndColumn())
          .build(length);
    }

    // These arrays are parallel.

    /** The indexes into the raw text. */
    private final int[] indexes;

    /** The source column associated with the corresponding index in indexes. */
    private final int[] columns;

    /** The source line numbers associated with the corresponding index in indexes. */
    private final int[] lines;

    private SourceOffsets(int[] indexes, int[] lines, int[] columns) {
      this.indexes = checkNotNull(indexes);
      this.lines = checkNotNull(lines);
      this.columns = checkNotNull(columns);
    }

    /** Returns the {@link Point} of the given offset within the given text. */
    Point getPoint(String text, int textIndex) {
      // the returned location is the place in the array where index would be inserted, so in
      // practice it is pointing at the smallest item in the array >= index.
      int location = Arrays.binarySearch(indexes, textIndex);
      // if 'textIndex' isn't in the list it returns (-insertion_point - 1) so if we want to know
      // the insertion point we need to do this transformation
      if (location < 0) {
        location = -(location + 1);
      }
      if (indexes[location] == textIndex) {
        // direct hit!
        return Point.create(lines[location], columns[location]);
      }
      // if it isn't a direct hit, we start at the previous item and walk forward through the array
      // counting character and newlines.
      return getLocationOf(text, location - 1, textIndex);
    }

    /**
     * Returns the Point where the character at {@code textIndex} is within the text. The scan
     * starts from {@code lines[startLocation]} which is guaranteed to be < textIndex.
     */
    private Point getLocationOf(String text, int startLocation, int textIndex) {
      int line = lines[startLocation];
      int column = columns[startLocation];
      int start = indexes[startLocation];
      for (int i = start; i < textIndex; i++) {
        char c = text.charAt(i);
        if (c == '\n') {
          line++;
          // N.B. we use 1 based indexes for columns (and lines, though that isn't relevant here)
          column = 1;
        } else if (c == '\r') {
          // look for \n as the next char to handled both \r and \r\n
          if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
            i++;
          }
          line++;
          column = 1;
        } else {
          column++;
        }
      }
      return Point.create(line, column);
    }

    /** Returns a new SourceOffsets object for the given subrange of the text. */
    SourceOffsets substring(int startTextIndex, int endTextIndex, String text) {
      checkArgument(startTextIndex >= 0);
      checkArgument(startTextIndex < endTextIndex);
      checkArgument(endTextIndex <= text.length());
      int substringLength = endTextIndex - startTextIndex;
      // subtract 1 from end since we want the endLocation to point at the last character rather
      // than just beyond it.
      endTextIndex--;

      int startLocation = Arrays.binarySearch(indexes, startTextIndex);
      // if 'startLocation' isn't in the list it returns (-insertion_point -1) so if we want to know
      // the insertion point we need to do this transformation
      if (startLocation < 0) {
        startLocation = -(startLocation + 1);
      }

      // calculate the initial point
      SourceOffsets.Builder builder = new SourceOffsets.Builder();
      int startLine;
      int startColumn;
      // if the index of the startlocation is the start index, set the startLine and startColumn
      // appropriately
      if (indexes[startLocation] == startTextIndex) {
        startLine = lines[startLocation];
        startColumn = columns[startLocation];
        builder.doAdd(0, lines[startLocation], columns[startLocation]);
      } else {
        // otherwise scan from the previous location forward to 'start'
        startLocation--;
        Point startPoint = getLocationOf(text, startLocation, startTextIndex);
        startLine = startPoint.line();
        startColumn = startPoint.column();
      }
      builder.doAdd(0, startLine, startColumn);

      if (startTextIndex == endTextIndex) {
        // special case
        builder.setEndLocation(startLine, startColumn);
        return builder.build(substringLength);
      }

      // copy over all offsets, taking care to modify the indexes
      int i = startLocation + 1;
      while (true) {
        int index = indexes[i];
        if (index < endTextIndex) {
          builder.doAdd(index - startTextIndex, lines[i], columns[i]);
        } else if (index == endTextIndex) {
          builder.setEndLocation(lines[i], columns[i]);
          break;
        } else if (index > endTextIndex) {
          // to find the end location we need to scan from the previous index
          Point endPoint = getLocationOf(text, i - 1, endTextIndex);
          builder.setEndLocation(endPoint.line(), endPoint.column());
          break;
        }
        i++;
      }

      return builder.build(substringLength);
    }

    /** Concatenates this SourceOffsets array with {@code other}. */
    SourceOffsets concat(SourceOffsets other) {
      // To concatenate we need to approximately just cat the arrays
      // since the last slot in the array corresponds to a psuedo location (the end of the string)
      // we should drop it when splicing.
      // we also need to modify all the indexes in other to be offset by the length of this offset's
      // string.

      int sizeToPreserve = indexes.length - 1;
      int lengthOfThis = indexes[indexes.length - 1];
      int newSize = sizeToPreserve + other.indexes.length;
      int[] newIndexes = Arrays.copyOf(indexes, newSize);
      int[] newLines = Arrays.copyOf(lines, newSize);
      int[] newColumns = Arrays.copyOf(columns, newSize);
      System.arraycopy(other.lines, 0, newLines, sizeToPreserve, other.lines.length);
      System.arraycopy(other.columns, 0, newColumns, sizeToPreserve, other.columns.length);
      // manually copy the indexes over so we can apply the offset
      for (int i = 0; i < other.indexes.length; i++) {
        newIndexes[i + sizeToPreserve] = other.indexes[i] + lengthOfThis;
      }
      return new SourceOffsets(newIndexes, newLines, newColumns);
    }

    /** Returns the sourcelocation for the whole span. */
    public SourceLocation getSourceLocation(String filePath) {
      return new SourceLocation(
          filePath, lines[0], columns[0], lines[lines.length - 1], columns[columns.length - 1]);
    }

    @Override
    public String toString() {
      return String.format(
          "SourceOffsets{\n  index:\t%s\n  lines:\t%s\n   cols:\t%s\n}",
          Arrays.toString(indexes), Arrays.toString(lines), Arrays.toString(columns));
    }

    /** Builder for SourceOffsets. */
    public static final class Builder {
      private int size;
      private int[] indexes = new int[16];
      private int[] lines = new int[16];
      private int[] columns = new int[16];
      private int endLine = -1;
      private int endCol = -1;

      public Builder add(int index, int startLine, int startCol, int endLine, int endCol) {
        checkArgument(index >= 0, "expected index to be non-negative: %s", index);
        checkArgument(startLine > 0, "expected startLine to be positive: %s", startLine);
        checkArgument(startCol > 0, "expected startCol to be positive: %s", startCol);
        checkArgument(endLine > 0, "expected endLine to be positive: %s", endLine);
        checkArgument(endCol > 0, "expected endCol to be positive: %s", endCol);

        if (size != 0 && index <= indexes[size - 1]) {
          throw new IllegalArgumentException("expected indexes to be added in increasing order");
        }
        doAdd(index, startLine, startCol);
        this.endLine = endLine;
        this.endCol = endCol;
        return this;
      }

      /** Update the end location only. */
      public Builder setEndLocation(int endLine, int endCol) {
        checkArgument(endLine > 0, "expected endLine to be positive: %s", endLine);
        checkArgument(endCol > 0, "expected endCol to be positive: %s", endCol);
        this.endLine = endLine;
        this.endCol = endCol;
        return this;
      }

      /** Delete all the offsets starting from the {@code from} index. */
      public Builder delete(int from) {
        // since we store end indexes in the list, we really just want to delete everything stricly
        // after 'from', this way if we leave 'from' as an end point
        int location = Arrays.binarySearch(indexes, 0, size, from);
        // if 'from' isn't in the list it returns (-insertion_point -1) so if we want to know the
        // insertion point we need to do this transformation
        if (location < 0) {
          location = -(location + 1);
        }
        size = location;
        return this;
      }

      public boolean isEmpty() {
        return size == 0;
      }

      /** Returns the ending line number or {@code -1} if it hasn't been set yet. */
      public int endLine() {
        return endLine;
      }

      /** Returns the ending column number or {@code -1} if it hasn't been set yet. */
      public int endColumn() {
        return endCol;
      }

      private void doAdd(int index, int line, int col) {
        if (size == indexes.length) {
          // expand by 1.5x each time
          int newCapacity = size + (size >> 1);
          indexes = Arrays.copyOf(indexes, newCapacity);
          lines = Arrays.copyOf(lines, newCapacity);
          columns = Arrays.copyOf(columns, newCapacity);
        }
        indexes[size] = index;
        lines[size] = line;
        columns[size] = col;
        size++;
      }

      /**
       * Builds the {@link SourceOffsets}.
       *
       * @param length the final length of the text.
       */
      public SourceOffsets build(int length) {
        // Set the last index as the length of the string and put the endLine/endCol there.
        // This simplifies some of the logic in SourceOffsets since it allows us to avoid
        // considering the end of the string as a special case.
        doAdd(length, endLine, endCol);

        checkArgument(size > 1, "The builder should have size >= 2, got %s", size);
        checkArgument(indexes[0] == 0, "expected first index to be zero, got: %s", indexes[0]);
        SourceOffsets built =
            new SourceOffsets(
                Arrays.copyOf(indexes, size),
                Arrays.copyOf(lines, size),
                Arrays.copyOf(columns, size));
        // by resetting size by 1 we undo the 'doAdd' of the endLine and endCol above and thus this
        // method becomes safe to call multiple times.
        size--;
        return built;
      }
    }
  }
}
