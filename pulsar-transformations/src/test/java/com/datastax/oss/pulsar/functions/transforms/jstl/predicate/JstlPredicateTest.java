/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.pulsar.functions.transforms.jstl.predicate;

import static org.testng.AssertJUnit.assertEquals;

import com.datastax.oss.pulsar.functions.transforms.TransformContext;
import com.datastax.oss.pulsar.functions.transforms.Utils;
import java.util.HashMap;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.functions.api.Record;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JstlPredicateTest {

  @Test(dataProvider = "keyValuePredicates")
  void testKeyValueAvro(String when, boolean match) {
    JstlPredicate predicate = new JstlPredicate(when);

    Record<GenericObject> record = Utils.createNestedAvroKeyValueRecord(2);
    Utils.TestContext context = new Utils.TestContext(record, new HashMap<>());
    TransformContext transformContext =
        new TransformContext(context, record.getValue().getNativeObject());

    assertEquals(predicate.test(transformContext), match);
  }

  @Test(
    expectedExceptions = IllegalArgumentException.class,
    expectedExceptionsMessageRegExp = "invalid when:.*"
  )
  void testInvalidWhen() {
    JstlPredicate predicate = new JstlPredicate("`invalid");

    Record<GenericObject> record = Utils.createNestedAvroKeyValueRecord(2);
    Utils.TestContext context = new Utils.TestContext(record, new HashMap<>());
    TransformContext transformContext =
        new TransformContext(context, record.getValue().getNativeObject());

    predicate.test(transformContext);
  }

  @Test(dataProvider = "primitiveKeyValuePredicates")
  void testPrimitiveKeyValueAvro(String when, TransformContext context, boolean match) {
    JstlPredicate predicate = new JstlPredicate(when);
    assertEquals(predicate.test(context), match);
  }

  @Test(dataProvider = "nestedKeyValuePredicates")
  void testNestedKeyValueAvro(String when, TransformContext context, boolean match) {
    JstlPredicate predicate = new JstlPredicate(when);
    assertEquals(predicate.test(context), match);
  }

  @Test(dataProvider = "primitivePredicates")
  void testPrimitiveValueAvro(String when, TransformContext context, boolean match) {
    JstlPredicate predicate = new JstlPredicate(when);
    assertEquals(predicate.test(context), match);
  }

  /** @return {"expression", "transform context" "expected match boolean"} */
  @DataProvider(name = "primitiveKeyValuePredicates")
  public static Object[][] primitiveKeyValuePredicates() {
    Schema<KeyValue<String, Integer>> keyValueSchema =
        Schema.KeyValue(Schema.STRING, Schema.INT32, KeyValueEncodingType.SEPARATED);

    KeyValue<String, Integer> keyValue = new KeyValue<>("key", 42);

    TransformContext primitiveKVContext =
        Utils.createContextWithPrimitiveRecord(keyValueSchema, keyValue, "");

    return new Object[][] {
      // match
      {"key=='key' && value==42", primitiveKVContext, true},
      // no-match
      {"key=='key' && value<42", primitiveKVContext, false},
    };
  }

  /** @return {"expression", "transform context" "expected match boolean"} */
  @DataProvider(name = "primitivePredicates")
  public static Object[][] primitivePredicates() {
    Schema<KeyValue<String, Integer>> keyValueSchema =
        Schema.KeyValue(Schema.STRING, Schema.INT32, KeyValueEncodingType.SEPARATED);
    KeyValue<String, Integer> keyValue = new KeyValue<>("key", 42);

    TransformContext primitiveStringContext =
        Utils.createContextWithPrimitiveRecord(Schema.STRING, "test-message", "header-key");
    TransformContext primitiveIntContext =
        Utils.createContextWithPrimitiveRecord(Schema.INT32, 33, "header-key");
    TransformContext primitiveKVContext =
        Utils.createContextWithPrimitiveRecord(keyValueSchema, keyValue, "header-key");

    return new Object[][] {
      // match
      {"value=='test-message'", primitiveStringContext, true},
      {"messageKey=='header-key'", primitiveStringContext, true},
      {"key=='header-key'", primitiveStringContext, true},
      {"value==33", primitiveIntContext, true},
      {"value eq 33", primitiveIntContext, true},
      {"value eq 32 + 1", primitiveIntContext, true},
      {"value eq 34 - 1", primitiveIntContext, true},
      {"value eq 66 / 2", primitiveIntContext, true},
      {"value eq 66 div 2", primitiveIntContext, true},
      {"value % 10 == 3", primitiveIntContext, true},
      {"value mod 10 == 3", primitiveIntContext, true},
      {"value>32", primitiveIntContext, true},
      {"value gt 32", primitiveIntContext, true},
      {"value<=33 && key=='header-key'", primitiveIntContext, true},
      {"key=='key' && value==42", primitiveKVContext, true},
      {"key=='key' and value==42", primitiveKVContext, true},
      {"key=='key1' || value==42", primitiveKVContext, true},
      {"key=='key1' or value==42", primitiveKVContext, true},
      {"key=='key' && value==42", primitiveKVContext, true},
      // no-match
      {"value=='test-message-'", primitiveStringContext, false},
      {"key!='header-key'", primitiveStringContext, false},
      {"key ne 'header-key'", primitiveStringContext, false},
      {"value==34", primitiveIntContext, false},
      {"value>33", primitiveIntContext, false},
      {"value<=20 && key=='test-key'", primitiveIntContext, false},
      {"value le 20 && key=='test-key'", primitiveIntContext, false},
    };
  }

  /** @return {"expression", "transform context" "expected match boolean"} */
  @DataProvider(name = "nestedKeyValuePredicates")
  public static Object[][] nestedKeyValuePredicates() {
    Schema<KeyValue<String, Integer>> keyValueSchema =
        Schema.KeyValue(Schema.STRING, Schema.INT32, KeyValueEncodingType.SEPARATED);

    Schema<KeyValue<KeyValue<String, Integer>, Integer>> nestedKeySchema =
        Schema.KeyValue(keyValueSchema, Schema.INT32, KeyValueEncodingType.SEPARATED);

    KeyValue<String, Integer> keyValue = new KeyValue<>("key1", 42);

    KeyValue<KeyValue<String, Integer>, Integer> nestedKeyKV = new KeyValue<>(keyValue, 3);

    GenericObject genericNestedKeyObject =
        new GenericObject() {
          @Override
          public SchemaType getSchemaType() {
            return SchemaType.KEY_VALUE;
          }

          @Override
          public Object getNativeObject() {
            return nestedKeyKV;
          }
        };

    Record<GenericObject> nestedKeyRecord =
        new Utils.TestRecord<>(nestedKeySchema, genericNestedKeyObject, null);

    TransformContext nestedKeyContext =
        new TransformContext(
            new Utils.TestContext(nestedKeyRecord, new HashMap<>()),
            nestedKeyRecord.getValue().getNativeObject());

    Schema<KeyValue<String, KeyValue<String, Integer>>> nestedValueSchema =
        Schema.KeyValue(Schema.STRING, keyValueSchema, KeyValueEncodingType.SEPARATED);

    KeyValue<String, KeyValue<String, Integer>> nestedValueKV = new KeyValue<>("key1", keyValue);

    GenericObject genericNestedValueObject =
        new GenericObject() {
          @Override
          public SchemaType getSchemaType() {
            return SchemaType.KEY_VALUE;
          }

          @Override
          public Object getNativeObject() {
            return nestedValueKV;
          }
        };

    Record<GenericObject> nestedValueRecord =
        new Utils.TestRecord<>(nestedValueSchema, genericNestedValueObject, null);

    TransformContext nestedValueContext =
        new TransformContext(
            new Utils.TestContext(nestedValueRecord, new HashMap<>()),
            nestedValueRecord.getValue().getNativeObject());
    return new Object[][] {
      // match
      {"key.key=='key1'", nestedKeyContext, true},
      {"key.value==42", nestedKeyContext, true},
      {"value==3", nestedKeyContext, true},
      {"value.key=='key1'", nestedValueContext, true},
      {"value.value==42", nestedValueContext, true},
      {"key=='key1'", nestedValueContext, true},
      // no match
      {"key.key=='key2'", nestedKeyContext, false},
      {"key.value<42", nestedKeyContext, false},
      {"value==4", nestedKeyContext, false},
      {"value.key=='key2'", nestedValueContext, false},
      {"value.value<42", nestedValueContext, false},
      {"key=='key2'", nestedValueContext, false},
    };
  }

  /** @return {"expression", "expected match boolean"} */
  @DataProvider(name = "keyValuePredicates")
  public static Object[][] keyValuePredicates() {
    return new Object[][] {
      // match
      {"key.level1String == 'level1_1'", true},
      {"key.level1Record.level2String == 'level2_1'", true},
      {"key.level1Record.level2Integer == 9", true},
      {"key.level1Record.level2Double == 8.8", true},
      {"key.level1Record.level2Array[0] == 'level2_1'", true},
      {"value.level1Record.level2Integer > 8", true},
      {"value.level1Record.level2Double < 8.9", true},
      {"value.level1Record.level2Array[0] == 'level2_1'", true},
      {"messageKey == 'key1'", true},
      {"destinationTopic == 'dest-topic-1'", true},
      {"topicName == 'topic-1'", true},
      {"properties.p1 == 'v1'", true},
      {"properties.p2 == 'v2'", true},
      // no match
      {"key.level1String == 'leVel1_1'", false},
      {"key.level1Record.random == 'level2_1'", false},
      {"key.level1Record.level2Integer != 9", false},
      {"key.level1Record.level2Double < 8.8", false},
      {"key.level1Record.level2Array[0] == 'non_existing_item'", false},
      {"key.randomKey == 'k1'", false},
      {"value.level1Record.level2Integer > 10", false},
      {"value.level1Record.level2Double < 0", false},
      {"value.randomValue < 0", false},
      {"messageKey == 'key2'", false},
      {"topicName != 'topic-1'", false},
      {"properties.p2 == 'v3'", false},
      {"randomHeader == 'h1'", false}
    };
  }
}
