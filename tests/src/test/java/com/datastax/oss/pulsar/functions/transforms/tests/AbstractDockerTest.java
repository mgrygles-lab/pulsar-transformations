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
/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsar.functions.transforms.tests;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Value;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.policies.data.FunctionStatus;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.SchemaType;
import org.testcontainers.containers.Network;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class AbstractDockerTest {

  private final String image;
  private Network network;
  private PulsarContainer pulsarContainer;
  private PulsarAdmin admin;
  private PulsarClient client;

  AbstractDockerTest(String image) {
    this.image = image;
  }

  @BeforeClass
  public void setup() throws Exception {
    network = Network.newNetwork();
    pulsarContainer = new PulsarContainer(network, image);
    // start Pulsar and wait for it to be ready to accept requests
    pulsarContainer.start();
    admin =
        PulsarAdmin.builder()
            .serviceHttpUrl(
                "http://localhost:" + pulsarContainer.getPulsarContainer().getMappedPort(8080))
            .build();
    client =
        PulsarClient.builder()
            .serviceUrl(
                "pulsar://localhost:" + pulsarContainer.getPulsarContainer().getMappedPort(6650))
            .build();
  }

  @AfterClass
  public void teardown() {
    if (client != null) {
      client.closeAsync();
    }
    if (admin != null) {
      admin.close();
    }
    if (pulsarContainer != null) {
      pulsarContainer.close();
    }
    if (network != null) {
      network.close();
    }
  }

  @Test
  public void testOutputPrimitive() throws Exception {
    String userConfig =
        (""
                + "{'steps': ["
                + "    {'type': 'unwrap-key-value'},"
                + "    {'type': 'cast', 'schema-type': 'STRING'}"
                + "]}")
            .replace("'", "\"");

    GenericRecord value = testTransformFunction(userConfig);
    assertEquals(value.getSchemaType(), SchemaType.STRING);
    assertEquals(value.getNativeObject(), "{\"c\": \"c\", \"d\": \"d\"}");
  }

  @Test
  public void testOutputKVPrimitive() throws Exception {
    String userConfig =
        ("" + "{'steps': [" + "    {'type': 'cast', 'schema-type': 'STRING'}" + "]}")
            .replace("'", "\"");

    GenericRecord value = testTransformFunction(userConfig);
    assertEquals(value.getSchemaType(), SchemaType.KEY_VALUE);
    KeyValue<String, String> keyValue = (KeyValue<String, String>) value.getNativeObject();
    assertEquals(keyValue.getKey(), "{\"a\": \"a\", \"b\": \"b\"}");
    assertEquals(keyValue.getValue(), "{\"c\": \"c\", \"d\": \"d\"}");
  }

  @Test
  public void testOutputAvro() throws Exception {
    String userConfig =
        (""
                + "{'steps': ["
                + "    {'type': 'unwrap-key-value'},"
                + "    {'type': 'drop-fields', 'fields': 'c'}"
                + "]}")
            .replace("'", "\"");

    GenericRecord value = testTransformFunction(userConfig);
    assertEquals(value.getSchemaType(), SchemaType.AVRO);
    org.apache.avro.generic.GenericRecord genericRecord =
        (org.apache.avro.generic.GenericRecord) value.getNativeObject();
    assertEquals(genericRecord.toString(), "{\"d\": \"d\"}");
  }

  @Test
  public void testOutputKVAvro() throws Exception {
    String userConfig =
        ("" + "{'steps': [" + "    {'type': 'drop-fields', 'fields': 'a,c'}" + "]}")
            .replace("'", "\"");

    GenericRecord value = testTransformFunction(userConfig);
    assertEquals(value.getSchemaType(), SchemaType.KEY_VALUE);
    KeyValue<GenericObject, GenericObject> keyValue =
        (KeyValue<GenericObject, GenericObject>) value.getNativeObject();

    assertEquals(keyValue.getKey().getSchemaType(), SchemaType.AVRO);
    assertEquals(keyValue.getKey().getNativeObject().toString(), "{\"b\": \"b\"}");
    assertEquals(keyValue.getValue().getSchemaType(), SchemaType.AVRO);
    assertEquals(keyValue.getValue().getNativeObject().toString(), "{\"d\": \"d\"}");
  }

  @Test
  public void testOutputKVAvroWhen() throws Exception {
    String userConfig =
        (""
            + "{\"steps\": ["
            + "    {\"type\": \"drop-fields\", \"fields\": \"a\", \"when\": \"key.a=='a'\"},"
            + "    {\"type\": \"drop-fields\", \"fields\": \"b\", \"when\": \"key.b!='b'\"},"
            + "    {\"type\": \"drop-fields\", \"fields\": \"c\", \"when\": \"value.c=='c'\"},"
            + "    {\"type\": \"drop-fields\", \"fields\": \"d\", \"when\": \"value.d!='d'\"}"
            + "]}");
    GenericRecord value = testTransformFunction(userConfig);
    assertEquals(value.getSchemaType(), SchemaType.KEY_VALUE);
    KeyValue<GenericObject, GenericObject> keyValue =
        (KeyValue<GenericObject, GenericObject>) value.getNativeObject();

    assertEquals(keyValue.getKey().getSchemaType(), SchemaType.AVRO);
    assertEquals(keyValue.getKey().getNativeObject().toString(), "{\"b\": \"b\"}");
    assertEquals(keyValue.getValue().getSchemaType(), SchemaType.AVRO);
    assertEquals(keyValue.getValue().getNativeObject().toString(), "{\"d\": \"d\"}");
  }

  private GenericRecord testTransformFunction(String userConfig)
      throws PulsarAdminException, InterruptedException,
          org.apache.pulsar.client.api.PulsarClientException {
    String functionId = UUID.randomUUID().toString();
    String inputTopic = "input-" + functionId;
    String outputTopic = "output-" + functionId;
    String functionName = "function-" + functionId;

    admin
        .topics()
        .createSubscription(
            inputTopic, String.format("public/default/%s", functionName), MessageId.latest);

    FunctionConfig functionConfig =
        FunctionConfig.builder()
            .tenant("public")
            .namespace("default")
            .name(functionName)
            .inputs(Collections.singletonList(inputTopic))
            .output(outputTopic)
            .jar("builtin://transforms")
            .runtime(FunctionConfig.Runtime.JAVA)
            .userConfig(
                new Gson().fromJson(userConfig, new TypeToken<Map<String, Object>>() {}.getType()))
            .build();

    admin.functions().createFunction(functionConfig, null);

    FunctionStatus functionStatus = null;
    for (int i = 0; i < 300; i++) {
      functionStatus = admin.functions().getFunctionStatus("public", "default", functionName);
      if (functionStatus.getNumRunning() == 1) {
        break;
      }
      Thread.sleep(100);
    }

    if (functionStatus.getNumRunning() != 1) {
      fail("Function didn't start in time");
    }

    Consumer<GenericRecord> consumer =
        client
            .newConsumer(Schema.AUTO_CONSUME())
            .topic(outputTopic)
            .subscriptionName(UUID.randomUUID().toString())
            .subscribe();

    Schema<KeyValue<Pojo1, Pojo2>> keyValueSchema =
        Schema.KeyValue(Schema.AVRO(Pojo1.class), Schema.AVRO(Pojo2.class));
    Producer<KeyValue<Pojo1, Pojo2>> producer =
        client.newProducer(keyValueSchema).topic(inputTopic).create();

    KeyValue<Pojo1, Pojo2> kv = new KeyValue<>(new Pojo1("a", "b"), new Pojo2("c", "d"));
    producer.newMessage().value(kv).send();

    Message<GenericRecord> message = consumer.receive(30, TimeUnit.SECONDS);
    GenericRecord value = message.getValue();
    assertNotNull(value);
    return value;
  }

  @Value
  private static class Pojo1 {
    String a;
    String b;
  }

  @Value
  private static class Pojo2 {
    String c;
    String d;
  }
}
