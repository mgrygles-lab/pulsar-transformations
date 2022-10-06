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

import com.datastax.oss.pulsar.functions.transforms.model.ComputeField;
import com.datastax.oss.pulsar.functions.transforms.model.ComputeFieldType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import org.apache.avro.AvroTypeException;
import org.apache.avro.Conversion;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.generic.IndexedRecord;

/** Computes a field dynamically based on JSTL expressions and adds it to the key or the value . */
@Builder
public class ComputeFieldStep implements TransformStep {

  @Builder.Default private final List<ComputeField> fields = new ArrayList<>();
  private final Map<org.apache.avro.Schema, org.apache.avro.Schema> keySchemaCache =
      new ConcurrentHashMap<>();
  private final Map<org.apache.avro.Schema, org.apache.avro.Schema> valueSchemaCache =
      new ConcurrentHashMap<>();
  private final Map<ComputeFieldType, org.apache.avro.Schema> fieldTypeToAvroSchemaCache =
      new ConcurrentHashMap<>();

  @Override
  public void process(TransformContext transformContext) {
    computeKeyFields(
        fields.stream().filter(f -> "key".equals(f.getScope())).collect(Collectors.toList()),
        transformContext);
    computeValueFields(
        fields.stream().filter(f -> "value".equals(f.getScope())).collect(Collectors.toList()),
        transformContext);
    computeHeaderFields(
        fields.stream().filter(f -> "header".equals(f.getScope())).collect(Collectors.toList()),
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
            default:
              throw new IllegalArgumentException("Invalid compute field name: " + field.getName());
          }
        });
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
    org.apache.avro.Schema avroSchema = record.getSchema();

    List<Schema.Field> computedFields =
        fields.stream().map(this::createAvroField).collect(Collectors.toList());

    Set<String> computedFieldNames =
        computedFields.stream().map(f -> f.name()).collect(Collectors.toSet());
    // New fields are the intersection between existing fields and computed fields. Computed fields
    // take precedence.
    List<Schema.Field> newFields =
        avroSchema
            .getFields()
            .stream()
            .filter(f -> !computedFieldNames.contains(f.name()))
            .map(
                f ->
                    new org.apache.avro.Schema.Field(
                        f.name(), f.schema(), f.doc(), f.defaultVal(), f.order()))
            .collect(Collectors.toList());
    newFields.addAll(computedFields);
    org.apache.avro.Schema newSchema =
        schemaCache.computeIfAbsent(
            avroSchema,
            schema ->
                org.apache.avro.Schema.createRecord(
                    avroSchema.getName(),
                    avroSchema.getDoc(),
                    avroSchema.getNamespace(),
                    avroSchema.isError(),
                    newFields));

    GenericRecordBuilder newRecordBuilder = new GenericRecordBuilder(newSchema);
    // Add original fields
    for (org.apache.avro.Schema.Field field : avroSchema.getFields()) {
      newRecordBuilder.set(field.name(), record.get(field.name()));
    }
    // Add computed fields
    for (ComputeField field : fields) {
      newRecordBuilder.set(
          field.getName(),
          getAvroValue(
              newSchema.getField(field.getName()).schema(),
              field.getEvaluator().evaluate(context)));
    }
    return newRecordBuilder.build();
  }

  private Object getAvroValue(Schema schema, Object value) {
    if (value == null) {
      return null;
    }

    LogicalType logicalType = getLogicalType(schema);
    if (logicalType == null) {
      return value;
    }

    // Avro logical type conversion: https://avro.apache.org/docs/1.8.2/spec.html#Logical+Types
    switch (logicalType.getName()) {
      case "date":
        validateLogicalType(value, schema.getLogicalType(), LocalDate.class);
        LocalDate localDate = (LocalDate) value;
        return (int) localDate.toEpochDay();
      case "time-millis":
        validateLogicalType(value, schema.getLogicalType(), LocalTime.class);
        LocalTime localTime = (LocalTime) value;
        return (int) (localTime.toNanoOfDay() / 1000000);
      case "timestamp-millis":
        validateLogicalType(value, schema.getLogicalType(), LocalDateTime.class);
        LocalDateTime localDateTime = (LocalDateTime) value;
        return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
      case "duration":
        validateLogicalType(value, schema.getLogicalType(), java.time.Duration.class);
        java.time.Duration duration = (java.time.Duration) value;
        return new DurationConversion().toFixed(duration, schema, schema.getLogicalType());
    }

    throw new IllegalArgumentException(
        String.format("Invalid logical type %s for value %s", schema.getLogicalType(), value));
  }

  private LogicalType getLogicalType(Schema schema) {
    if (!schema.isUnion()) {
      return schema.getLogicalType();
    }

    return schema
        .getTypes()
        .stream()
        .filter(subSchema -> subSchema.getLogicalType() != null)
        .map(Schema::getLogicalType)
        .findAny()
        .orElse(null);
  }

  void validateLogicalType(Object value, LogicalType logicalType, Class expectedClass) {
    if (!(value.getClass().equals(expectedClass))) {
      throw new IllegalArgumentException(
          String.format("Invalid value %s for logical type %s", value, logicalType));
    }
  }

  private Schema.Field createAvroField(ComputeField field) {
    Schema avroSchema = getAvroSchema(field.getType());
    Object defaultValue = null;
    if (field.isOptional()) {
      avroSchema = SchemaBuilder.unionOf().nullType().and().type(avroSchema).endUnion();
      defaultValue = Schema.Field.NULL_DEFAULT_VALUE;
    }
    return new Schema.Field(field.getName(), avroSchema, null, defaultValue);
  }

  private Schema getAvroSchema(ComputeFieldType type) {
    Schema.Type schemaType;
    switch (type) {
      case STRING:
        schemaType = Schema.Type.STRING;
        break;
      case INT32:
      case DATE:
      case TIME:
        schemaType = Schema.Type.INT;
        break;
      case INT64:
      case DATETIME:
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
      case DURATION:
        schemaType = Schema.Type.FIXED;
        break;
      default:
        throw new UnsupportedOperationException("Unsupported compute field type: " + type);
    }

    return fieldTypeToAvroSchemaCache.computeIfAbsent(
        type,
        key -> {
          // Handle logical types: https://avro.apache.org/docs/1.10.2/spec.html#Logical+Types
          switch (key) {
            case DATE:
              return LogicalTypes.date().addToSchema(Schema.create(schemaType));
            case TIME:
              return LogicalTypes.timeMillis().addToSchema(Schema.create(schemaType));
            case DATETIME:
              return LogicalTypes.timestampMillis().addToSchema(Schema.create(schemaType));
            case DURATION:
              return DURATION_TYPE.addToSchema(DURATION_SCHEMA);
            default:
              return Schema.create(schemaType);
          }
        });
  }

  public static final Duration DURATION_TYPE = new Duration(0, 0, 0);
  public static final Schema DURATION_SCHEMA = Schema.createFixed("duration", "", "", 3);

  @Getter
  public static class Duration extends LogicalType {
    private static final String MONTHS_PROP = "months";
    private static final String DAYS_PROP = "days";
    private static final String MILLISECONDS_PROP = "milliseconds";

    private final int months;
    private final int days;
    private final int milliseconds;

    private Duration(int months, int days, int milliseconds) {
      super("duration");
      this.months = months;
      this.days = days;
      this.milliseconds = milliseconds;
    }

    private Duration(Schema schema) {
      super("duration");
      this.months = this.hasProperty(schema, "months") ? this.getInt(schema, "months") : 0;
      this.days = this.hasProperty(schema, "days") ? this.getInt(schema, "days") : 0;
      this.milliseconds = this.hasProperty(schema, "milliseconds") ? this.getInt(schema, "milliseconds") : 0;
    }

    public Schema addToSchema(Schema schema) {
      super.addToSchema(schema);
      schema.addProp("months", this.months);
      schema.addProp("days", this.days);
      schema.addProp("milliseconds", this.milliseconds);
      return schema;
    }

    public void validate(Schema schema) {
      super.validate(schema);
      if (schema.getType() != Schema.Type.FIXED) {
        throw new IllegalArgumentException("Logical type duration must be backed by fixed");
      } else if (this.months < 0) {
        throw new IllegalArgumentException("Invalid duration months: " + this.months + " (must be positive)");
      } else if (this.days < 0) {
        throw new IllegalArgumentException("Invalid duration days: " + this.days + " (must be positive)");
      } else if (this.milliseconds < 0) {
        throw new IllegalArgumentException("Invalid duration milliseconds: " + this.milliseconds + " (must be positive)");
      }
    }

    private boolean hasProperty(Schema schema, String name) {
      return schema.getObjectProp(name) != null;
    }

    private int getInt(Schema schema, String name) {
      Object obj = schema.getObjectProp(name);
      if (obj instanceof Integer) {
        return (Integer)obj;
      } else {
        throw new IllegalArgumentException("Expected int " + name + ": " + (obj == null ? "null" : obj + ":" + obj.getClass().getSimpleName()));
      }
    }

    public boolean equals(Object o) {
      if (this == o) {
        return true;
      } else if (o != null && this.getClass() == o.getClass()) {
        Duration duration = (Duration)o;
        if (this.months != duration.months) {
          return false;
        } else if(this.days != duration.days) {
          return false;
        } else {
          return this.milliseconds == duration.milliseconds;
        }
      } else {
        return false;
      }
    }

    public int hashCode() {
      int result = this.months;
      result = 31 * result + this.days;
      result = 31 * result + this.milliseconds;
      return result;
    }
  }
  static class DurationConversion extends Conversion<Duration> {

    @Override
    public Class<Duration> getConvertedType() {
      return Duration.class;
    }

    @Override
    public String getLogicalTypeName() {
      return "duration";
    }

    public GenericFixed toFixed(java.time.Duration value, Schema schema, LogicalType type) {
      value = validate((Duration)type, value);
      byte[] bytes = new byte[schema.getFixedSize()];
      bytes[0] = (byte)value.get(ChronoUnit.MONTHS);
      bytes[1] = (byte)value.get(ChronoUnit.DAYS);
      bytes[2] = (byte)value.get(ChronoUnit.MILLIS);
      return new GenericData.Fixed(schema, bytes);
    }

    private static java.time.Duration validate(final Duration duration, java.time.Duration value) {
        return value;
    }
  }
}
