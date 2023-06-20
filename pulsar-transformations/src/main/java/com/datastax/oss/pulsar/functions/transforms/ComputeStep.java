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

import static org.apache.pulsar.common.schema.SchemaType.AVRO;

import com.datastax.oss.pulsar.functions.transforms.jstl.JstlTypeConverter;
import com.datastax.oss.pulsar.functions.transforms.model.ComputeField;
import com.datastax.oss.pulsar.functions.transforms.model.ComputeFieldType;
import com.datastax.oss.pulsar.functions.transforms.util.AvroUtil;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Builder;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;

/** Computes a field dynamically based on JSTL expressions and adds it to the key or the value . */
@Builder
public class ComputeStep implements TransformStep {

  public static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);
  @Builder.Default private final List<ComputeField> fields = new ArrayList<>();
  private final Map<org.apache.avro.Schema, org.apache.avro.Schema> keySchemaCache =
      new ConcurrentHashMap<>();
  private final Map<org.apache.avro.Schema, org.apache.avro.Schema> valueSchemaCache =
      new ConcurrentHashMap<>();
  private final Map<ComputeFieldType, org.apache.avro.Schema> fieldTypeToAvroSchemaCache =
      new ConcurrentHashMap<>();

  @Override
  public void process(TransformContext transformContext) {
    computePrimitiveField(
        fields.stream().filter(f -> "primitive".equals(f.getScope())).collect(Collectors.toList()),
        transformContext);
    computeKeyFields(
        fields.stream().filter(f -> "key".equals(f.getScope())).collect(Collectors.toList()),
        transformContext);
    computeValueFields(
        fields.stream().filter(f -> "value".equals(f.getScope())).collect(Collectors.toList()),
        transformContext);
    computeHeaderFields(
        fields.stream().filter(f -> "header".equals(f.getScope())).collect(Collectors.toList()),
        transformContext);
    computeHeaderPropertiesFields(
        fields
            .stream()
            .filter(f -> "header.properties".equals(f.getScope()))
            .collect(Collectors.toList()),
        transformContext);
  }

  public void computeValueFields(List<ComputeField> fields, TransformContext context) {
    if (context.getValueSchema().getSchemaInfo().getType() == AVRO) {
      GenericRecord avroRecord = (GenericRecord) context.getValueObject();
      GenericRecord newRecord = computeFields(fields, avroRecord, valueSchemaCache, context);
      if (avroRecord != newRecord) {
        context.setValueModified(true);
      }
      context.setValueObject(newRecord);
    }
  }

  public void computePrimitiveField(List<ComputeField> fields, TransformContext context) {
    fields
        .stream()
        .filter(f -> "key".equals(f.getName()))
        .findFirst()
        .ifPresent(
            field -> {
              if (context.getKeySchema() != null
                  && context.getKeySchema().getSchemaInfo().getType().isPrimitive()) {
                Object newKey = field.getEvaluator().evaluate(context);
                org.apache.pulsar.client.api.Schema<?> newSchema;
                if (field.getType() != null) {
                  newSchema = getPrimitiveSchema(field.getType());
                } else {
                  newSchema = getPrimitiveSchema(newKey);
                }
                context.setKeyObject(newKey);
                context.setKeySchema(newSchema);
              }
            });

    fields
        .stream()
        .filter(f -> "value".equals(f.getName()))
        .findFirst()
        .ifPresent(
            field -> {
              if (context.getValueSchema().getSchemaInfo().getType().isPrimitive()) {
                Object newValue = field.getEvaluator().evaluate(context);
                org.apache.pulsar.client.api.Schema<?> newSchema;
                if (field.getType() != null) {
                  newSchema = getPrimitiveSchema(field.getType());
                } else {
                  newSchema = getPrimitiveSchema(newValue);
                }
                context.setValueObject(newValue);
                context.setValueSchema(newSchema);
              }
            });
  }

  public void computeKeyFields(List<ComputeField> fields, TransformContext context) {
    if (context.getKeyObject() != null
        && context.getValueSchema().getSchemaInfo().getType() == AVRO) {
      GenericRecord avroRecord = (GenericRecord) context.getKeyObject();
      GenericRecord newRecord = computeFields(fields, avroRecord, keySchemaCache, context);
      if (avroRecord != newRecord) {
        context.setKeyModified(true);
      }
      context.setKeyObject(newRecord);
    }
  }

  public void computeHeaderFields(List<ComputeField> fields, TransformContext context) {
    fields.forEach(
        field -> {
          switch (field.getName()) {
            case "destinationTopic":
              String topic = validateAndGetString(field, context);
              context.setOutputTopic(topic);
              break;
            case "messageKey":
              String key = validateAndGetString(field, context);
              context.setKey(key);
              break;
            default:
              throw new IllegalArgumentException("Invalid compute field name: " + field.getName());
          }
        });
  }

  public void computeHeaderPropertiesFields(List<ComputeField> fields, TransformContext context) {
    Map<String, String> properties =
        fields
            .stream()
            .map(field -> Map.entry(field.getName(), validateAndGetString(field, context)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    context.setProperties(properties);
  }

  private String validateAndGetString(ComputeField field, TransformContext context) {
    Object value = field.getEvaluator().evaluate(context);
    if (value instanceof String) {
      return (String) value;
    }

    throw new IllegalArgumentException(
        String.format(
            "Invalid compute field type. " + "Name: %s, Type: %s, Expected Type: %s",
            field.getName(), value == null ? "null" : value.getClass().getSimpleName(), "String"));
  }

  private GenericRecord computeFields(
      List<ComputeField> fields,
      GenericRecord record,
      Map<org.apache.avro.Schema, org.apache.avro.Schema> schemaCache,
      TransformContext context) {

    // Evaluate computed fields
    Map<Schema.Field, Object> evaluatedFields =
        new LinkedHashMap<>(); // preserves the insertion order of keys
    for (ComputeField field : fields) {
      Object value = field.getEvaluator().evaluate(context);
      ComputeFieldType type = field.getType() == null ? getFieldType(value) : field.getType();
      Schema.Field avroField = createAvroField(field, type, value);
      evaluatedFields.put(avroField, getAvroValue(avroField.schema(), value));
    }

    org.apache.avro.Schema avroSchema = record.getSchema();
    Set<String> computedFieldNames =
        evaluatedFields.keySet().stream().map(Schema.Field::name).collect(Collectors.toSet());
    // original fields - overwritten fields
    List<Schema.Field> nonOverwrittenFields =
        avroSchema
            .getFields()
            .stream()
            .filter(f -> !computedFieldNames.contains(f.name()))
            .map(
                f ->
                    new org.apache.avro.Schema.Field(
                        f.name(), f.schema(), f.doc(), f.defaultVal(), f.order()))
            .collect(Collectors.toList());
    // allFields is the intersection between existing fields and computed fields. Computed fields
    // take precedence.
    List<Schema.Field> allFields = new ArrayList<>();
    allFields.addAll(nonOverwrittenFields);
    allFields.addAll(evaluatedFields.keySet().stream().collect(Collectors.toList()));
    org.apache.avro.Schema newSchema =
        schemaCache.computeIfAbsent(
            avroSchema,
            schema ->
                org.apache.avro.Schema.createRecord(
                    avroSchema.getName(),
                    avroSchema.getDoc(),
                    avroSchema.getNamespace(),
                    avroSchema.isError(),
                    allFields));

    GenericRecordBuilder newRecordBuilder = new GenericRecordBuilder(newSchema);
    // Add original fields
    for (org.apache.avro.Schema.Field field : nonOverwrittenFields) {
      newRecordBuilder.set(field.name(), record.get(field.name()));
    }
    // Add computed fields
    for (Map.Entry<Schema.Field, Object> entry : evaluatedFields.entrySet()) {
      // set the field by name to preserve field position
      newRecordBuilder.set(entry.getKey().name(), entry.getValue());
    }
    return newRecordBuilder.build();
  }

  private Object getAvroValue(Schema schema, Object value) {
    if (value == null) {
      return null;
    }

    if (value instanceof byte[]) {
      return ByteBuffer.wrap((byte[]) value);
    }

    if (value instanceof Byte || value instanceof Short) {
      return ((Number) value).intValue();
    }

    LogicalType logicalType = AvroUtil.getLogicalType(schema);
    if (logicalType == null) {
      return value;
    }

    // Avro logical type conversion: https://avro.apache.org/docs/1.8.2/spec.html#Logical+Types
    switch (logicalType.getName()) {
      case "date":
        return getAvroDate(value, schema.getLogicalType());
      case "time-millis":
        return getAvroTimeMillis(value, schema.getLogicalType());
      case "timestamp-millis":
        return getAvroTimestampMillis(value, schema.getLogicalType());
      case "decimal":
        return getAvroDecimal(value, schema.getLogicalType());
    }

    throw new IllegalArgumentException(
        String.format("Invalid logical type %s for value %s", schema.getLogicalType(), value));
  }

  private Long getAvroTimestampMillis(Object value, LogicalType logicalType) {
    validateLogicalType(value, logicalType, Instant.class, Timestamp.class, LocalDateTime.class);
    return JstlTypeConverter.INSTANCE.coerceToType(value, Long.class);
  }

  private Integer getAvroDate(Object value, LogicalType logicalType) {
    validateLogicalType(value, logicalType, Date.class, LocalDate.class);
    return (value instanceof LocalDate)
        ? Math.toIntExact(((LocalDate) value).toEpochDay())
        : Math.toIntExact((((Date) value).getTime() / MILLIS_PER_DAY));
  }

  private Integer getAvroTimeMillis(Object value, LogicalType logicalType) {
    validateLogicalType(value, logicalType, Time.class, LocalTime.class);
    return JstlTypeConverter.INSTANCE.coerceToType(value, Integer.class);
  }

  private BigDecimal getAvroDecimal(Object value, LogicalType logicalType) {
    validateLogicalType(value, logicalType, BigDecimal.class);
    return JstlTypeConverter.INSTANCE.coerceToType(value, BigDecimal.class);
  }

  void validateLogicalType(Object value, LogicalType logicalType, Class<?>... expectedClasses) {
    for (Class<?> clazz : expectedClasses) {
      if ((value.getClass().equals(clazz))) {
        return;
      }
    }
    throw new IllegalArgumentException(
        String.format("Invalid java type %s for logical type %s", value.getClass(), logicalType));
  }

  private Schema.Field createAvroField(ComputeField field, ComputeFieldType type, Object value) {
    Schema avroSchema = getAvroSchema(type, value);
    Object defaultValue = null;
    if (field.isOptional()) {
      avroSchema = SchemaBuilder.unionOf().nullType().and().type(avroSchema).endUnion();
      defaultValue = Schema.Field.NULL_DEFAULT_VALUE;
    }
    return new Schema.Field(field.getName(), avroSchema, null, defaultValue);
  }

  private Schema getAvroSchema(ComputeFieldType type, Object value) {
    Schema.Type schemaType;
    switch (type) {
      case STRING:
        schemaType = Schema.Type.STRING;
        break;
      case INT8:
      case INT16:
      case INT32:
      case DATE:
      case LOCAL_DATE:
      case TIME:
      case LOCAL_TIME:
        schemaType = Schema.Type.INT;
        break;
      case INT64:
      case DATETIME:
      case TIMESTAMP:
      case INSTANT:
      case LOCAL_DATE_TIME:
        schemaType = Schema.Type.LONG;
        break;
      case FLOAT:
        schemaType = Schema.Type.FLOAT;
        break;
      case DOUBLE:
        schemaType = Schema.Type.DOUBLE;
        break;
      case BOOLEAN:
        schemaType = Schema.Type.BOOLEAN;
        break;
      case BYTES:
        schemaType = Schema.Type.BYTES;
        break;
      case DECIMAL:
        // disable caching for decimal schema because the schema is different for each precision and
        // scale combo and will result in an arbitrary numbers of schemas
        // See: https://avro.apache.org/docs/1.10.2/spec.html#Decimal
        BigDecimal decimal = (BigDecimal) value;
        return LogicalTypes.decimal(decimal.precision(), decimal.scale())
            .addToSchema(Schema.create(Schema.Type.BYTES));
      default:
        throw new UnsupportedOperationException("Unsupported compute field type: " + type);
    }

    return fieldTypeToAvroSchemaCache.computeIfAbsent(
        type,
        key -> {
          // Handle logical types: https://avro.apache.org/docs/1.10.2/spec.html#Logical+Types
          Schema schema = Schema.create(schemaType);
          switch (key) {
            case DATE:
            case LOCAL_DATE:
              return LogicalTypes.date().addToSchema(schema);
            case TIME:
            case LOCAL_TIME:
              return LogicalTypes.timeMillis().addToSchema(schema);
            case DATETIME:
            case INSTANT:
            case TIMESTAMP:
            case LOCAL_DATE_TIME:
              return LogicalTypes.timestampMillis().addToSchema(schema);
            default:
              return schema;
          }
        });
  }

  private org.apache.pulsar.client.api.Schema<?> getPrimitiveSchema(ComputeFieldType type) {
    org.apache.pulsar.client.api.Schema<?> schema;
    switch (type) {
      case STRING:
        schema = org.apache.pulsar.client.api.Schema.STRING;
        break;
      case INT8:
        schema = org.apache.pulsar.client.api.Schema.INT8;
        break;
      case INT16:
        schema = org.apache.pulsar.client.api.Schema.INT16;
        break;
      case INT32:
        schema = org.apache.pulsar.client.api.Schema.INT32;
        break;
      case INT64:
        schema = org.apache.pulsar.client.api.Schema.INT64;
        break;
      case FLOAT:
        schema = org.apache.pulsar.client.api.Schema.FLOAT;
        break;
      case DOUBLE:
        schema = org.apache.pulsar.client.api.Schema.DOUBLE;
        break;
      case BOOLEAN:
        schema = org.apache.pulsar.client.api.Schema.BOOL;
        break;
      case DATE:
        schema = org.apache.pulsar.client.api.Schema.DATE;
        break;
      case LOCAL_DATE:
        schema = org.apache.pulsar.client.api.Schema.LOCAL_DATE;
        break;
      case TIME:
        schema = org.apache.pulsar.client.api.Schema.TIME;
        break;
      case LOCAL_TIME:
        schema = org.apache.pulsar.client.api.Schema.LOCAL_TIME;
        break;
      case LOCAL_DATE_TIME:
        schema = org.apache.pulsar.client.api.Schema.LOCAL_DATE_TIME;
        break;
      case DATETIME:
      case INSTANT:
        schema = org.apache.pulsar.client.api.Schema.INSTANT;
        break;
      case TIMESTAMP:
        schema = org.apache.pulsar.client.api.Schema.TIMESTAMP;
        break;
      case BYTES:
        schema = org.apache.pulsar.client.api.Schema.BYTES;
        break;
      default:
        throw new UnsupportedOperationException("Unsupported compute field type: " + type);
    }

    return schema;
  }

  private org.apache.pulsar.client.api.Schema<?> getPrimitiveSchema(Object value) {
    if (value == null) {
      throw new UnsupportedOperationException("Cannot get schema from null value");
    }
    if (value.getClass().equals(String.class)) {
      return org.apache.pulsar.client.api.Schema.STRING;
    }
    if (value.getClass().equals(byte[].class)) {
      return org.apache.pulsar.client.api.Schema.BYTES;
    }
    if (value.getClass().equals(Boolean.class)) {
      return org.apache.pulsar.client.api.Schema.BOOL;
    }
    if (value.getClass().equals(Byte.class)) {
      return org.apache.pulsar.client.api.Schema.INT8;
    }
    if (value.getClass().equals(Short.class)) {
      return org.apache.pulsar.client.api.Schema.INT16;
    }
    if (value.getClass().equals(Integer.class)) {
      return org.apache.pulsar.client.api.Schema.INT32;
    }
    if (value.getClass().equals(Long.class)) {
      return org.apache.pulsar.client.api.Schema.INT64;
    }
    if (value.getClass().equals(Float.class)) {
      return org.apache.pulsar.client.api.Schema.FLOAT;
    }
    if (value.getClass().equals(Double.class)) {
      return org.apache.pulsar.client.api.Schema.DOUBLE;
    }
    if (value.getClass().equals(Date.class)) {
      return org.apache.pulsar.client.api.Schema.DATE;
    }
    if (value.getClass().equals(Timestamp.class)) {
      return org.apache.pulsar.client.api.Schema.TIMESTAMP;
    }
    if (value.getClass().equals(Time.class)) {
      return org.apache.pulsar.client.api.Schema.TIME;
    }
    if (value.getClass().equals(LocalDateTime.class)) {
      return org.apache.pulsar.client.api.Schema.LOCAL_DATE_TIME;
    }
    if (value.getClass().equals(LocalDate.class)) {
      return org.apache.pulsar.client.api.Schema.LOCAL_DATE;
    }
    if (value.getClass().equals(LocalTime.class)) {
      return org.apache.pulsar.client.api.Schema.LOCAL_TIME;
    }
    if (value.getClass().equals(Instant.class)) {
      return org.apache.pulsar.client.api.Schema.INSTANT;
    }
    throw new UnsupportedOperationException("Got an unsupported type: " + value.getClass());
  }

  private ComputeFieldType getFieldType(Object value) {
    if (value == null) {
      throw new UnsupportedOperationException("Cannot get field type from null value");
    }
    if (value instanceof CharSequence) {
      return ComputeFieldType.STRING;
    }
    if (value instanceof ByteBuffer || value.getClass().equals(byte[].class)) {
      return ComputeFieldType.BYTES;
    }
    if (value.getClass().equals(Boolean.class)) {
      return ComputeFieldType.BOOLEAN;
    }
    if (value.getClass().equals(Byte.class)) {
      return ComputeFieldType.INT8;
    }
    if (value.getClass().equals(Short.class)) {
      return ComputeFieldType.INT16;
    }
    if (value.getClass().equals(Integer.class)) {
      return ComputeFieldType.INT32;
    }
    if (value.getClass().equals(Long.class)) {
      return ComputeFieldType.INT64;
    }
    if (value.getClass().equals(Float.class)) {
      return ComputeFieldType.FLOAT;
    }
    if (value.getClass().equals(Double.class)) {
      return ComputeFieldType.DOUBLE;
    }
    if (value.getClass().equals(Date.class)) {
      return ComputeFieldType.DATE;
    }
    if (value.getClass().equals(Timestamp.class)) {
      return ComputeFieldType.TIMESTAMP;
    }
    if (value.getClass().equals(Time.class)) {
      return ComputeFieldType.TIME;
    }
    if (value.getClass().equals(LocalDateTime.class)) {
      return ComputeFieldType.LOCAL_DATE_TIME;
    }
    if (value.getClass().equals(LocalDate.class)) {
      return ComputeFieldType.LOCAL_DATE;
    }
    if (value.getClass().equals(LocalTime.class)) {
      return ComputeFieldType.LOCAL_TIME;
    }
    if (value.getClass().equals(Instant.class)) {
      return ComputeFieldType.INSTANT;
    }
    if (value.getClass().equals(BigDecimal.class)) {
      return ComputeFieldType.DECIMAL;
    }
    throw new UnsupportedOperationException("Got an unsupported type: " + value.getClass());
  }
}
