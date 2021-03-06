/*
 * Copyright 2016 Google Inc.
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

package com.google.template.soy.passes;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.StringSubject;
import com.google.template.soy.base.internal.IncrementingIdGenerator;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.ExplodingErrorReporter;
import com.google.template.soy.soyparse.SoyFileParser;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.types.SoyTypeRegistry;
import java.io.StringReader;
import junit.framework.TestCase;

public final class HtmlRewritePassTest extends TestCase {

  public void testTags() {
    TemplateNode node = runPass("<div></div>");
    assertThat(node.getChild(0)).isInstanceOf(HtmlOpenTagNode.class);
    assertThat(node.getChild(1)).isInstanceOf(HtmlCloseTagNode.class);
    assertThatSourceString(node).isEqualTo("<div></div>");
    assertThatASTString(node).isEqualTo("HTML_OPEN_TAG_NODE\n" + "HTML_CLOSE_TAG_NODE\n");
  }

  public void testAttributes() {
    TemplateNode node = runPass("<div class=\"foo\"></div>");
    assertThatSourceString(node).isEqualTo("<div class=\"foo\"></div>");
    String structure =
        ""
            + "HTML_OPEN_TAG_NODE\n"
            + "  HTML_ATTRIBUTE_NODE\n"
            + "    RAW_TEXT_NODE\n"
            + "    HTML_ATTRIBUTE_VALUE_NODE\n"
            + "      RAW_TEXT_NODE\n"
            + "HTML_CLOSE_TAG_NODE\n";
    assertThatASTString(node).isEqualTo(structure);

    // test alternate quotation marks

    node = runPass("<div class='foo'></div>");
    assertThatSourceString(node).isEqualTo("<div class='foo'></div>");
    assertThatASTString(node).isEqualTo(structure);

    node = runPass("<div class=foo></div>");
    assertThatSourceString(node).isEqualTo("<div class=foo></div>");
    assertThatASTString(node).isEqualTo(structure);
  }

  public void testLetAttributes() {
    TemplateNode node = runPass("{let $foo kind=\"attributes\"}class='foo'{/let}");
    assertThatSourceString(node).isEqualTo("{let $foo kind=\"attributes\"}class='foo'{/let}");
    String structure =
        ""
            + "LET_CONTENT_NODE\n"
            + "  HTML_ATTRIBUTE_NODE\n"
            + "    RAW_TEXT_NODE\n"
            + "    HTML_ATTRIBUTE_VALUE_NODE\n"
            + "      RAW_TEXT_NODE\n";
    assertThatASTString(node).isEqualTo(structure);
  }

  public void testSelfClosingTag() {
    TemplateNode node = runPass("<input/>");
    assertThatSourceString(node).isEqualTo("<input/>");

    // NOTE: the whitespace difference
    node = runPass("<input />");
    assertThatSourceString(node).isEqualTo("<input/>");
  }

  public void testTextNodes() {
    TemplateNode node = runPass("x x<div>content</div> <div>{sp}</div>");
    assertThatSourceString(node).isEqualTo("x x<div>content</div> <div> </div>");
    assertThatASTString(node)
        .isEqualTo(
            "RAW_TEXT_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n");
  }

  public void testDynamicTagName() {
    TemplateNode node = runPass("{let $t : 'div' /}<{$t}>content</{$t}>");
    assertThatSourceString(node).isEqualTo("{let $t : 'div' /}<{$t}>content</{$t}>");
    // NOTE: the print nodes don't end up in the AST due to how TagName works, this is probably a
    // bad idea in the long run.  We should probably make TagName be a node.
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n");
  }

  public void testDynamicAttributeValue() {
    TemplateNode node = runPass("{let $t : 'x' /}<div a={$t}>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div a={$t}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "");
    // try alternate quotes
    node = runPass("{let $t : 'x' /}<div a=\"{$t}\">content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div a=\"{$t}\">content</div>");

    node = runPass("{let $t : 'x' /}<div a='{$t}'>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div a='{$t}'>content</div>");
  }

  public void testDynamicAttribute() {
    TemplateNode node = runPass("{let $t : 'x' /}<div {$t}>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div {$t}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "");

    // and with a value
    node = runPass("{let $t : 'x' /}<div {$t}=x>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div {$t}=x>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "");
  }

  public void testConditionalAttribute() {
    TemplateNode node = runPass("{let $t : 'x' /}<div {if $t}foo{else}bar{/if}>content</div>");
    assertThatSourceString(node)
        .isEqualTo("{let $t : 'x' /}<div{if $t} foo{else} bar{/if}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  IF_NODE\n"
                + "    IF_COND_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "    IF_ELSE_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n");
  }

  public void testConditionalAttributeValue() {
    TemplateNode node =
        runPass("{let $t : 'x' /}<div class=\"{if $t}foo{else}bar{/if}\">content</div>");
    assertThatSourceString(node)
        .isEqualTo("{let $t : 'x' /}<div class=\"{if $t}foo{else}bar{/if}\">content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      IF_NODE\n"
                + "        IF_COND_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "        IF_ELSE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n");
  }

  // TODO(lukes): ideally these would all be implemented in the CompilerIntegrationTests but the
  // ContextualAutoescaper rejects these forms.  once we stop 'desuraging' prior to the autoescaper
  // we can move these tests over.

  public void testConditionalContextMerging() {
    TemplateNode node = runPass("{@param p : ?}<div {if $p}foo=bar{else}baz{/if}>");
    assertThatSourceString(node).isEqualTo("<div{if $p} foo=bar{else} baz{/if}>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  IF_NODE\n"
                + "    IF_COND_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "        HTML_ATTRIBUTE_VALUE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "    IF_ELSE_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "");
    node = runPass("{@param p : ?}<div {if $p}class=x{else}style=\"baz\"{/if}>");
    assertThatSourceString(node).isEqualTo("<div{if $p} class=x{else} style=\"baz\"{/if}>");

    node = runPass("{@param p : ?}<div {if $p}class='x'{else}style=\"baz\"{/if}>");
    assertThatSourceString(node).isEqualTo("<div{if $p} class='x'{else} style=\"baz\"{/if}>");
  }

  // Ideally, we wouldn't support this pattern since it adds a fair bit of complexity
  public void testConditionalQuotedAttributeValues() {
    TemplateNode node = runPass("{@param p : ?}<div x={if $p}'foo'{else}'bar'{/if} {$p}>");
    assertThatSourceString(node).isEqualTo("<div x={if $p}'foo'{else}'bar'{/if} {$p}> ");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    IF_NODE\n"
                + "      IF_COND_NODE\n"
                + "        HTML_ATTRIBUTE_VALUE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "      IF_ELSE_NODE\n"
                + "        HTML_ATTRIBUTE_VALUE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "");

    node =
        runPass(
            "{@param p : ?}{@param p2 : ?}<div x={if $p}{if $p2}'foo'{else}'bar'{/if}"
                + "{else}{if $p2}'foo'{else}'bar'{/if}{/if} {$p}>");
    assertThatSourceString(node)
        .isEqualTo(
            "<div x={if $p}{if $p2}'foo'{else}'bar'{/if}{else}{if $p2}'foo'{else}'bar'{/if}{/if}"
                + " {$p}> ");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    IF_NODE\n"
                + "      IF_COND_NODE\n"
                + "        IF_NODE\n"
                + "          IF_COND_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "          IF_ELSE_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "      IF_ELSE_NODE\n"
                + "        IF_NODE\n"
                + "          IF_COND_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "          IF_ELSE_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "");
  }

  public void testConditionalUnquotedAttributeValue() {
    TemplateNode node = runPass("{@param p : ?}<div class={if $p}x{else}y{/if}>");
    assertThatSourceString(node).isEqualTo("<div class={if $p}x{else}y{/if}>");
  }

  private static TemplateNode runPass(String input) {
    return runPass(input, ExplodingErrorReporter.get());
  }

  /** Parses the given input as a template content. */
  private static TemplateNode runPass(String input, ErrorReporter errorReporter) {
    String soyFile =
        Joiner.on('\n')
            .join("{namespace ns}", "", "{template .t stricthtml=\"true\"}", input, "{/template}");
    IncrementingIdGenerator nodeIdGen = new IncrementingIdGenerator();
    SoyFileNode node =
        new SoyFileParser(
                new SoyTypeRegistry(),
                nodeIdGen,
                new StringReader(soyFile),
                SoyFileKind.SRC,
                "test.soy",
                errorReporter)
            .parseSoyFile();
    if (node != null) {
      new HtmlRewritePass(ImmutableList.of("stricthtml"), errorReporter).run(node, nodeIdGen);
      return node.getChild(0);
    }
    return null;
  }

  private static StringSubject assertThatSourceString(TemplateNode node) {
    SoyFileNode parent = SoyTreeUtils.cloneNode(node.getParent());
    new DesugarHtmlNodesPass().run(parent, new IncrementingIdGenerator());
    StringBuilder sb = new StringBuilder();
    parent.getChild(0).appendSourceStringForChildren(sb);
    return assertThat(sb.toString());
  }

  private static StringSubject assertThatASTString(TemplateNode node) {
    return assertThat(buildAstString(node, 0, new StringBuilder()).toString());
  }

  private static StringBuilder buildAstString(ParentSoyNode<?> node, int indent, StringBuilder sb) {
    for (SoyNode child : node.getChildren()) {
      sb.append(Strings.repeat("  ", indent)).append(child.getKind()).append('\n');
      if (child instanceof ParentSoyNode) {
        buildAstString((ParentSoyNode<?>) child, indent + 1, sb);
      }
    }
    return sb;
  }
}
