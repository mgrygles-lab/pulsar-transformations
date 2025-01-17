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
package com.datastax.oss.pulsar.functions.transforms;

import com.datastax.oss.pulsar.functions.transforms.model.JsonRecord;
import com.datastax.oss.pulsar.functions.transforms.util.AvroUtil;
import com.datastax.oss.pulsar.functions.transforms.util.JsonConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Conversions;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.KeyValueSchema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.functions.api.Context;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.api.utils.FunctionRecord;

@Slf4j
@Data
public class TransformContext {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final Context context;
  private Schema<?> keySchema;
  private Object keyObject;
  private boolean keyModified;
  private Schema<?> valueSchema;
  private Object valueObject;
  private boolean valueModified;
  private KeyValueEncodingType keyValueEncodingType;
  private String key;
  private Map<String, String> properties;
  private String outputTopic;
  private boolean dropCurrentRecord;

  public TransformContext(Context context, Object value) {
    Record<?> currentRecord = context.getCurrentRecord();
    this.context = context;
    this.outputTopic = context.getOutputTopic();
    Schema<?> schema = currentRecord.getSchema();
    if (schema instanceof KeyValueSchema && value instanceof KeyValue) {
      KeyValueSchema<?, ?> kvSchema = (KeyValueSchema<?, ?>) schema;
      KeyValue<?, ?> kv = (KeyValue<?, ?>) value;
      this.keySchema = kvSchema.getKeySchema();
      this.keyObject =
          this.keySchema.getSchemaInfo().getType().isStruct()
              ? ((GenericObject) kv.getKey()).getNativeObject()
              : kv.getKey();
      this.valueSchema = kvSchema.getValueSchema();
      this.valueObject =
          this.valueSchema.getSchemaInfo().getType().isStruct()
              ? ((GenericObject) kv.getValue()).getNativeObject()
              : kv.getValue();
      this.keyValueEncodingType = kvSchema.getKeyValueEncodingType();
    } else {
      this.valueSchema = schema;
      this.valueObject = value;
      this.key = currentRecord.getKey().orElse(null);
    }
  }

  public Record<GenericObject> send() throws IOException {
    if (dropCurrentRecord) {
      return null;
    }
    if (keyModified
        && keySchema != null
        && keySchema.getSchemaInfo().getType() == SchemaType.AVRO) {
      GenericRecord genericRecord = (GenericRecord) keyObject;
      keySchema = Schema.NATIVE_AVRO(genericRecord.getSchema());
      keyObject = serializeGenericRecord(genericRecord);
    }
    if (valueModified
        && valueSchema != null
        && valueSchema.getSchemaInfo().getType() == SchemaType.AVRO) {
      GenericRecord genericRecord = (GenericRecord) valueObject;
      valueSchema = Schema.NATIVE_AVRO(genericRecord.getSchema());
      valueObject = serializeGenericRecord(genericRecord);
    }

    Schema outputSchema;
    Object outputObject;
    GenericObject recordValue = (GenericObject) context.getCurrentRecord().getValue();
    if (keySchema != null) {
      outputSchema = Schema.KeyValue(keySchema, valueSchema, keyValueEncodingType);
      Object outputKeyObject =
          !keyModified && keySchema.getSchemaInfo().getType().isStruct()
              ? ((KeyValue<?, ?>) recordValue.getNativeObject()).getKey()
              : keyObject;
      Object outputValueObject =
          !valueModified && valueSchema.getSchemaInfo().getType().isStruct()
              ? ((KeyValue<?, ?>) recordValue.getNativeObject()).getValue()
              : valueObject;
      outputObject = new KeyValue<>(outputKeyObject, outputValueObject);
    } else {
      outputSchema = valueSchema;
      outputObject =
          !valueModified && valueSchema.getSchemaInfo().getType().isStruct()
              ? recordValue
              : valueObject;
    }

    if (log.isDebugEnabled()) {
      log.debug("output {} schema {}", outputObject, outputSchema);
    }

    FunctionRecord.FunctionRecordBuilder<GenericObject> recordBuilder =
        context
            .newOutputRecordBuilder(outputSchema)
            .destinationTopic(outputTopic)
            .value(outputObject)
            .properties(getOutputProperties());

    if (keySchema == null && key != null) {
      recordBuilder.key(key);
    }

    return recordBuilder.build();
  }

  public void addProperty(String key, String value) {
    if (this.properties == null) {
      this.properties = new HashMap<>();
    }
    this.properties.put(key, value);
  }

  public Map<String, String> getOutputProperties() {
    if (this.properties == null) {
      return context.getCurrentRecord().getProperties();
    }

    if (context.getCurrentRecord().getProperties() == null) {
      return this.properties;
    }

    Map<String, String> mergedProperties = new HashMap<>();
    mergedProperties.putAll(this.context.getCurrentRecord().getProperties());
    mergedProperties.putAll(
        this.properties); // Computed props will overwrite current record props if the keys match

    return mergedProperties;
  }

  public static byte[] serializeGenericRecord(GenericRecord record) throws IOException {
    GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(record.getSchema());
    // enable Decimal conversion, otherwise attempting to serialize java.math.BigDecimal will throw
    // ClassCastException
    writer.getData().addLogicalTypeConversion(new Conversions.DecimalConversion());
    ByteArrayOutputStream oo = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().directBinaryEncoder(oo, null);
    writer.write(record, encoder);
    return oo.toByteArray();
  }

  public void addOrReplaceValueFields(
      SchemaType schemaType,
      Map<org.apache.avro.Schema.Field, Object> newFields,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache) {
    if (schemaType == SchemaType.AVRO) {
      addOrReplaceAvroValueFields(newFields, schemaCache);
    } else if (schemaType == SchemaType.JSON) {
      addOrReplaceJsonValueFields(newFields, schemaCache);
    }
  }

  private void addOrReplaceAvroValueFields(
      Map<org.apache.avro.Schema.Field, Object> newFields,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache) {
    if (valueSchema.getSchemaInfo().getType() == SchemaType.AVRO) {
      GenericRecord avroRecord = (GenericRecord) valueObject;
      GenericRecord newRecord =
          AvroUtil.addOrReplaceAvroRecordFields(avroRecord, newFields, schemaCache);
      if (avroRecord != newRecord) {
        valueModified = true;
      }
      valueObject = newRecord;
    }
  }

  private void addOrReplaceAvroKeyFields(
      Map<org.apache.avro.Schema.Field, Object> newFields,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache) {
    if (keySchema.getSchemaInfo().getType() == SchemaType.AVRO) {
      GenericRecord avroRecord = (GenericRecord) keyObject;
      GenericRecord newRecord =
          AvroUtil.addOrReplaceAvroRecordFields(avroRecord, newFields, schemaCache);
      if (avroRecord != newRecord) {
        keyModified = true;
      }
      keyObject = newRecord;
    }
  }

  public void addOrReplaceKeyFields(
      SchemaType schemaType,
      Map<org.apache.avro.Schema.Field, Object> newFields,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache) {
    if (schemaType == SchemaType.AVRO) {
      addOrReplaceAvroKeyFields(newFields, schemaCache);
    } else if (schemaType == SchemaType.JSON) {
      addOrReplaceJsonKeyFields(newFields, schemaCache);
    }
  }

  private void addOrReplaceJsonValueFields(
      Map<org.apache.avro.Schema.Field, Object> newFields,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache) {
    if (valueSchema.getSchemaInfo().getType() == SchemaType.JSON) {
      org.apache.avro.Schema schema =
          AvroUtil.addOrReplaceAvroSchemaFields(
              (org.apache.avro.Schema) valueSchema.getNativeSchema().orElseThrow(),
              newFields.keySet(),
              schemaCache);
      valueSchema = new JsonNodeSchema(schema);
      ObjectNode json = (ObjectNode) valueObject;
      newFields.forEach((field, value) -> json.set(field.name(), OBJECT_MAPPER.valueToTree(value)));
      valueObject = json;
      valueModified = true;
    }
  }

  private void addOrReplaceJsonKeyFields(
      Map<org.apache.avro.Schema.Field, Object> newFields,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache) {
    if (keySchema.getSchemaInfo().getType() == SchemaType.JSON) {
      org.apache.avro.Schema schema =
          AvroUtil.addOrReplaceAvroSchemaFields(
              (org.apache.avro.Schema) keySchema.getNativeSchema().orElseThrow(),
              newFields.keySet(),
              schemaCache);
      keySchema = new JsonNodeSchema(schema);
      ObjectNode json = (ObjectNode) keyObject;
      newFields.forEach((field, value) -> json.set(field.name(), OBJECT_MAPPER.valueToTree(value)));
      keyObject = json;
      keyModified = true;
    }
  }

  public JsonRecord toJsonRecord() {
    JsonRecord jsonRecord = new JsonRecord();
    if (keySchema != null) {
      jsonRecord.setKey(toJsonSerializable(keySchema, keyObject));
    } else {
      jsonRecord.setKey(key);
    }
    jsonRecord.setValue(toJsonSerializable(valueSchema, valueObject));
    jsonRecord.setDestinationTopic(outputTopic);

    jsonRecord.setProperties(getOutputProperties());
    Record<?> currentRecord = context.getCurrentRecord();
    currentRecord.getEventTime().ifPresent(jsonRecord::setEventTime);
    currentRecord.getTopicName().ifPresent(jsonRecord::setTopicName);
    return jsonRecord;
  }

  private static Object toJsonSerializable(
      org.apache.pulsar.client.api.Schema<?> schema, Object val) {
    if (schema == null || schema.getSchemaInfo().getType().isPrimitive()) {
      return val;
    }
    switch (schema.getSchemaInfo().getType()) {
      case AVRO:
        // TODO: do better than the double conversion AVRO -> JsonNode -> Map
        return OBJECT_MAPPER.convertValue(
            JsonConverter.toJson((org.apache.avro.generic.GenericRecord) val),
            new TypeReference<Map<String, Object>>() {});
      case JSON:
        return OBJECT_MAPPER.convertValue(val, new TypeReference<Map<String, Object>>() {});
      default:
        throw new UnsupportedOperationException(
            "Unsupported schemaType " + schema.getSchemaInfo().getType());
    }
  }

  public static String toJson(Object object) throws JsonProcessingException {
    return OBJECT_MAPPER.writeValueAsString(object);
  }

  public void setResultField(
      Object content,
      String fieldName,
      org.apache.avro.Schema fieldSchema,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> avroKeySchemaCache,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> avroValueSchemaCache) {
    if (fieldName == null || fieldName.equals("value")) {
      valueSchema = Schema.STRING;
      valueObject = content;
    } else if (fieldName.equals("key")) {
      keySchema = Schema.STRING;
      keyObject = content;
    } else if (fieldName.equals("destinationTopic")) {
      outputTopic = content.toString();
    } else if (fieldName.equals("messageKey")) {
      key = content.toString();
    } else if (fieldName.startsWith("properties.")) {
      String propertyKey = fieldName.substring("properties.".length());
      addProperty(propertyKey, content.toString());
    } else if (fieldName.startsWith("value.")) {
      String valueFieldName = fieldName.substring("value.".length());
      org.apache.avro.Schema.Field fieldSchemaField =
          new org.apache.avro.Schema.Field(valueFieldName, fieldSchema, null, null);
      addOrReplaceValueFields(valueSchema.getSchemaInfo().getType(), Map.of(fieldSchemaField, content), avroValueSchemaCache);
    } else if (fieldName.startsWith("key.")) {
      String keyFieldName = fieldName.substring("key.".length());
      org.apache.avro.Schema.Field fieldSchemaField =
          new org.apache.avro.Schema.Field(keyFieldName, fieldSchema, null, null);
      addOrReplaceKeyFields(keySchema.getSchemaInfo().getType(), Map.of(fieldSchemaField, content), avroKeySchemaCache);
    }
  }
}
