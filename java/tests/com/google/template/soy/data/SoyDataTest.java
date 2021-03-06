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

package com.google.template.soy.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.StringData;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for SoyData.
 *
 */
@RunWith(JUnit4.class)
public class SoyDataTest {

  @Test
  public void testCreateFromExistingData() {

    assertTrue(SoyData.createFromExistingData(null) instanceof NullData);
    assertEquals("boo", SoyData.createFromExistingData(StringData.forValue("boo")).stringValue());
    assertEquals("boo", SoyData.createFromExistingData("boo").stringValue());
    assertEquals(true, SoyData.createFromExistingData(true).booleanValue());
    assertEquals(8, SoyData.createFromExistingData(8).integerValue());
    assertEquals(
        "foo",
        ((SoyMapData) SoyData.createFromExistingData(ImmutableMap.of("boo", "foo")))
            .getString("boo"));
    assertEquals(
        "goo",
        ((SoyListData) SoyData.createFromExistingData(ImmutableList.of("goo"))).getString(0));
    assertEquals(
        "hoo", ((SoyListData) SoyData.createFromExistingData(ImmutableSet.of("hoo"))).getString(0));

    assertEquals(3.14, SoyData.createFromExistingData(3.14).floatValue(), 0.0);
    assertEquals(3.14F, (float) SoyData.createFromExistingData(3.14F).floatValue(), 0.0f);
  }
}
